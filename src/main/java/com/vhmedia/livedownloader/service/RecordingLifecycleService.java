package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.config.RemuxTaskExecutor;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.RemuxException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.media.FfmpegRecordingService;
import com.vhmedia.livedownloader.media.FfmpegRemuxService;
import com.vhmedia.livedownloader.media.JobStatusChangedEvent;
import com.vhmedia.livedownloader.media.RecordingExitReason;
import com.vhmedia.livedownloader.media.RecordingExitResult;
import com.vhmedia.livedownloader.media.RecordingFinishedEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import com.vhmedia.livedownloader.util.UrlRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates stop / finish / remux transitions with idempotent guards against races.
 */
@Slf4j
@Service
public class RecordingLifecycleService {

	private static final Set<LiveJobStatus> STOP_IDEMPOTENT_STATUSES = EnumSet.of(
			LiveJobStatus.STOPPING,
			LiveJobStatus.REMUXING,
			LiveJobStatus.COMPLETED
	);

	private static final Set<LiveJobStatus> REMUX_SOURCE_STATUSES = EnumSet.of(
			LiveJobStatus.READY,
			LiveJobStatus.RECORDING,
			LiveJobStatus.STOPPING
	);

	private static final Set<LiveJobStatus> FAILABLE_STATUSES = EnumSet.of(
			LiveJobStatus.READY,
			LiveJobStatus.RECORDING,
			LiveJobStatus.STOPPING,
			LiveJobStatus.REMUXING
	);

	private final LiveDownloadJobRepository jobRepository;
	private final FfmpegRecordingService ffmpegRecordingService;
	private final FfmpegRemuxService ffmpegRemuxService;
	private final RecordingPathResolver pathResolver;
	private final RemuxTaskExecutor remuxTaskExecutor;
	private final MediaProperties mediaProperties;
	private final ApplicationEventPublisher eventPublisher;
	private final RecordingProgressPersistenceService progressPersistenceService;
	private final TransactionTemplate transactionTemplate;

	public RecordingLifecycleService(
			LiveDownloadJobRepository jobRepository,
			FfmpegRecordingService ffmpegRecordingService,
			FfmpegRemuxService ffmpegRemuxService,
			RecordingPathResolver pathResolver,
			RemuxTaskExecutor remuxTaskExecutor,
			MediaProperties mediaProperties,
			ApplicationEventPublisher eventPublisher,
			RecordingProgressPersistenceService progressPersistenceService,
			PlatformTransactionManager transactionManager
	) {
		this.jobRepository = jobRepository;
		this.ffmpegRecordingService = ffmpegRecordingService;
		this.ffmpegRemuxService = ffmpegRemuxService;
		this.pathResolver = pathResolver;
		this.remuxTaskExecutor = remuxTaskExecutor;
		this.mediaProperties = mediaProperties;
		this.eventPublisher = eventPublisher;
		this.progressPersistenceService = progressPersistenceService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * Idempotent stop: RECORDING → STOPPING, then graceful FFmpeg quit.
	 * Repeated calls while STOPPING/REMUXING/COMPLETED are no-ops.
	 */
	@Transactional
	public void requestStop(UUID jobId) {
		jobRepository.findById(jobId)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + jobId));

		Instant now = Instant.now();
		int updated = jobRepository.transitionStatus(jobId, LiveJobStatus.RECORDING, LiveJobStatus.STOPPING, now);
		if (updated == 0) {
			LiveDownloadJob current = jobRepository.findById(jobId)
					.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + jobId));
			if (STOP_IDEMPOTENT_STATUSES.contains(current.getStatus())) {
				log.info("Stop ignored (idempotent) jobId={} status={}", jobId, current.getStatus());
				return;
			}
			throw new InvalidRecordingStateException("Cannot stop job in status " + current.getStatus());
		}

		log.info("Job marked STOPPING jobId={}", jobId);
		eventPublisher.publishEvent(new JobStatusChangedEvent(jobId, LiveJobStatus.STOPPING));
		boolean processFound = ffmpegRecordingService.requestGracefulStop(jobId);
		if (!processFound) {
			log.info(
					"Stop requested but process already gone jobId={} (natural end race); waiting for finished event",
					jobId
			);
		}
	}

	/**
	 * Handles FFmpeg process termination. Safe against stop/natural-end/remux races.
	 */
	@EventListener
	public void onRecordingFinished(RecordingFinishedEvent event) {
		RecordingExitResult result = event.result();
		UUID jobId = result.getJobId();
		log.info(
				"Recording finished event jobId={} reason={} exitCode={}",
				jobId,
				result.getReason(),
				result.getExitCode()
		);

		if (result.getReason() == RecordingExitReason.FAILED) {
			markFailed(jobId, safeError(result.getErrorMessage()));
			return;
		}

		Path tsPath = resolveTsPath(jobId, result.getOutputPath());
		TsValidation validation = validateTempTs(tsPath);
		if (!validation.valid()) {
			markFailed(jobId, validation.message());
			return;
		}

		boolean claimed = claimRemux(jobId, result, validation.size());
		if (!claimed) {
			log.info("Remux already claimed or job terminal jobId={}", jobId);
			return;
		}

		eventPublisher.publishEvent(new JobStatusChangedEvent(
				jobId,
				LiveJobStatus.REMUXING,
				result.getDownloadedBytes() != null ? result.getDownloadedBytes() : validation.size(),
				result.getDurationMillis()
		));

		remuxTaskExecutor.execute(() -> runRemux(jobId, tsPath));
	}

	private boolean claimRemux(UUID jobId, RecordingExitResult result, long tsSize) {
		Boolean claimed = transactionTemplate.execute(status -> {
			LiveDownloadJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || !REMUX_SOURCE_STATUSES.contains(job.getStatus())) {
				return false;
			}

			job.setStatus(LiveJobStatus.REMUXING);
			if (result.getDownloadedBytes() != null) {
				job.setDownloadedBytes(result.getDownloadedBytes());
			} else {
				job.setDownloadedBytes(tsSize);
			}
			if (result.getDurationMillis() != null) {
				job.setDurationMillis(result.getDurationMillis());
			}
			if (job.getStoppedAt() == null) {
				job.setStoppedAt(Instant.now());
			}
			if (result.getOutputPath() != null && (job.getTempFilePath() == null || job.getTempFilePath().isBlank())) {
				job.setTempFilePath(result.getOutputPath().toString());
			}
			jobRepository.save(job);
			return true;
		});
		return Boolean.TRUE.equals(claimed);
	}

	private void runRemux(UUID jobId, Path tsPath) {
		try {
			LiveDownloadJob job = jobRepository.findById(jobId)
					.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + jobId));

			Path mp4Path = pathResolver.resolveMp4Path(job.getOutputBaseName());
			long mp4Size = ffmpegRemuxService.remux(tsPath, mp4Path);

			Boolean completed = transactionTemplate.execute(status -> {
				LiveDownloadJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
				if (locked == null || locked.getStatus() != LiveJobStatus.REMUXING) {
					return false;
				}
				locked.setStatus(LiveJobStatus.COMPLETED);
				locked.setFinalFilePath(mp4Path.toString());
				locked.setDownloadedBytes(mp4Size);
				locked.setCompletedAt(Instant.now());
				jobRepository.save(locked);
				return true;
			});

			if (Boolean.TRUE.equals(completed)) {
				log.info("Job completed after remux jobId={} mp4={} sizeBytes={}", jobId, mp4Path.getFileName(), mp4Size);
				progressPersistenceService.clear(jobId);
				eventPublisher.publishEvent(new JobStatusChangedEvent(jobId, LiveJobStatus.COMPLETED, mp4Size, null));
				maybeDeleteTempTs(tsPath);
			} else {
				log.warn("Remux succeeded but job was no longer REMUXING jobId={}", jobId);
			}
		} catch (RemuxException ex) {
			log.error("Remux failed jobId={} (temp TS retained for recovery)", jobId, ex);
			markFailed(jobId, safeError(ex.getMessage()));
		} catch (RuntimeException ex) {
			log.error("Remux failed jobId={} (temp TS retained for recovery)", jobId, ex);
			markFailed(jobId, "Remux failed");
		}
	}

	/**
	 * Deletes intermediate TS only after successful remux when configured.
	 * Never deletes TS on remux failure so the recording remains recoverable.
	 */
	private void maybeDeleteTempTs(Path tsPath) {
		if (!mediaProperties.isDeleteTempAfterRemux()) {
			log.debug("Keeping temp TS after remux (delete-temp-after-remux=false) path={}", tsPath.getFileName());
			return;
		}
		try {
			boolean deleted = Files.deleteIfExists(tsPath);
			if (deleted) {
				log.info("Deleted temp TS after successful remux path={}", tsPath.getFileName());
			}
		} catch (IOException ex) {
			log.warn("Failed to delete temp TS after remux path={}: {}", tsPath.getFileName(), ex.getMessage());
		}
	}

	private void markFailed(UUID jobId, String message) {
		Boolean failed = transactionTemplate.execute(status -> {
			LiveDownloadJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || !FAILABLE_STATUSES.contains(job.getStatus())) {
				log.info("Failure transition skipped (already terminal) jobId={}", jobId);
				return false;
			}
			job.setStatus(LiveJobStatus.FAILED);
			job.setErrorMessage(truncate(message, 2000));
			if (job.getStoppedAt() == null) {
				job.setStoppedAt(Instant.now());
			}
			jobRepository.save(job);
			return true;
		});
		if (Boolean.TRUE.equals(failed)) {
			progressPersistenceService.clear(jobId);
			eventPublisher.publishEvent(new JobStatusChangedEvent(jobId, LiveJobStatus.FAILED));
		}
	}

	private Path resolveTsPath(UUID jobId, Path eventPath) {
		if (eventPath != null) {
			return eventPath;
		}
		LiveDownloadJob job = jobRepository.findById(jobId)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + jobId));
		if (job.getTempFilePath() != null && !job.getTempFilePath().isBlank()) {
			return pathResolver.toPath(job.getTempFilePath());
		}
		return pathResolver.resolveTsPath(job.getOutputBaseName());
	}

	private static TsValidation validateTempTs(Path tsPath) {
		if (tsPath == null) {
			return TsValidation.invalid("Temp TS path is missing");
		}
		if (!Files.exists(tsPath)) {
			return TsValidation.invalid("Temp TS file does not exist");
		}
		try {
			long size = Files.size(tsPath);
			if (size <= 0) {
				return TsValidation.invalid("Temp TS file is empty");
			}
			return TsValidation.valid(size);
		} catch (IOException ex) {
			return TsValidation.invalid("Unable to read temp TS file");
		}
	}

	private static String safeError(String message) {
		if (message == null || message.isBlank()) {
			return "Recording failed";
		}
		return truncate(UrlRedactor.redactInText(message), 2000);
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "...";
	}

	private record TsValidation(boolean valid, long size, String message) {
		static TsValidation valid(long size) {
			return new TsValidation(true, size, null);
		}

		static TsValidation invalid(String message) {
			return new TsValidation(false, 0L, message);
		}
	}
}
