package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.dto.response.RecordingJobResponse;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.ConcurrentRecordingLimitException;
import com.vhmedia.livedownloader.exception.FfmpegStartException;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.MediaExecutableMissingException;
import com.vhmedia.livedownloader.exception.NoVideoStreamException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.exception.StorageException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.media.FfmpegRecordingService;
import com.vhmedia.livedownloader.media.JobStatusChangedEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.OutputBaseNameGenerator;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import com.vhmedia.livedownloader.util.StreamUrlValidator;
import com.vhmedia.livedownloader.util.UrlRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class RecordingJobService {

	private static final Set<LiveJobStatus> DELETABLE_STATUSES = EnumSet.of(
			LiveJobStatus.CREATED,
			LiveJobStatus.PROBING,
			LiveJobStatus.READY,
			LiveJobStatus.COMPLETED,
			LiveJobStatus.FAILED,
			LiveJobStatus.INTERRUPTED
	);

	private static final Set<LiveJobStatus> ACTIVE_PIPELINE_STATUSES = EnumSet.of(
			LiveJobStatus.CREATED,
			LiveJobStatus.PROBING,
			LiveJobStatus.READY,
			LiveJobStatus.RECORDING,
			LiveJobStatus.STOPPING,
			LiveJobStatus.REMUXING
	);

	private final StreamUrlValidator streamUrlValidator;
	private final FfprobeService ffprobeService;
	private final FfmpegRecordingService ffmpegRecordingService;
	private final LiveDownloadJobRepository jobRepository;
	private final RecordingPathResolver pathResolver;
	private final ApplicationEventPublisher eventPublisher;

	public RecordingJobService(
			StreamUrlValidator streamUrlValidator,
			FfprobeService ffprobeService,
			FfmpegRecordingService ffmpegRecordingService,
			LiveDownloadJobRepository jobRepository,
			RecordingPathResolver pathResolver,
			ApplicationEventPublisher eventPublisher
	) {
		this.streamUrlValidator = streamUrlValidator;
		this.ffprobeService = ffprobeService;
		this.ffmpegRecordingService = ffmpegRecordingService;
		this.jobRepository = jobRepository;
		this.pathResolver = pathResolver;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Validates URL, probes stream, persists job, starts FFmpeg asynchronously, returns immediately.
	 */
	public RecordingJobResponse createAndStart(String streamUrl) {
		String trimmedUrl = streamUrl == null ? null : streamUrl.trim();
		streamUrlValidator.validate(trimmedUrl);
		pathResolver.assertWritableAndHasFreeSpace();

		UUID jobId = UUID.randomUUID();
		String outputBaseName = OutputBaseNameGenerator.generate();
		Path tempTs = pathResolver.resolveTsPath(outputBaseName);
		Path finalMp4 = pathResolver.resolveMp4Path(outputBaseName);

		LiveDownloadJob job = LiveDownloadJob.builder()
				.id(jobId)
				.originalUrl(trimmedUrl)
				.outputBaseName(outputBaseName)
				.tempFilePath(tempTs.toString())
				.finalFilePath(finalMp4.toString())
				.status(LiveJobStatus.CREATED)
				.build();
		job = jobRepository.save(job);
		log.info("Created recording job id={} url={} base={}", jobId, UrlRedactor.redact(trimmedUrl), outputBaseName);

		job.setStatus(LiveJobStatus.PROBING);
		job = jobRepository.save(job);

		StreamProbeResult probeResult;
		try {
			probeResult = ffprobeService.probe(trimmedUrl);
		} catch (StreamProbeTimeoutException ex) {
			log.warn("Probe timed out jobId={} url={}", jobId, UrlRedactor.redact(trimmedUrl));
			failJob(job, "Stream probe timed out");
			throw new StreamProbeTimeoutException("Stream probe timed out.");
		} catch (MediaExecutableMissingException ex) {
			log.error("Probe failed jobId={} category=EXECUTABLE_MISSING reason={}", jobId, ex.getMessage());
			failJob(job, "Media tools (ffmpeg/ffprobe) are not available");
			throw new MediaExecutableMissingException("Media tools (ffmpeg/ffprobe) are not available.");
		} catch (StreamProbeException ex) {
			log.warn("Probe failed jobId={} reason={}", jobId, ex.getMessage());
			failJob(job, "Unable to read stream");
			throw new StreamProbeException("Unable to read stream.");
		} catch (RuntimeException ex) {
			log.error("Unexpected probe error jobId={}", jobId, ex);
			failJob(job, "Stream probe failed");
			throw ex;
		}

		applyProbeMetadata(job, probeResult);

		if (!probeResult.isHasVideo()) {
			failJob(job, "Stream has no video track");
			throw new NoVideoStreamException("Stream has no video track; recording requires video");
		}

		job.setStatus(LiveJobStatus.READY);
		job = jobRepository.save(job);

		// Persist RECORDING before scheduling FFmpeg so finish/fail handlers never see READY.
		Instant startedAt = Instant.now();
		job.setStatus(LiveJobStatus.RECORDING);
		job.setStartedAt(startedAt);
		job = jobRepository.save(job);
		eventPublisher.publishEvent(new JobStatusChangedEvent(jobId, LiveJobStatus.RECORDING));

		try {
			ffmpegRecordingService.startRecording(jobId, trimmedUrl, tempTs);
		} catch (ConcurrentRecordingLimitException ex) {
			failJob(job, ex.getMessage());
			throw ex;
		} catch (FfmpegStartException ex) {
			log.error("Failed to start recording jobId={}", jobId, ex);
			failJob(job, "Failed to start recording");
			throw ex;
		} catch (RuntimeException ex) {
			log.error("Failed to schedule recording jobId={}", jobId, ex);
			failJob(job, "Failed to start recording");
			throw new FfmpegStartException("Failed to start recording", ex);
		}

		log.info("Recording started jobId={} output={}", jobId, tempTs.getFileName());
		return RecordingJobMapper.toResponse(job);
	}

	@Transactional(readOnly = true)
	public Page<RecordingJobResponse> list(LiveJobStatus status, boolean activeOnly, Pageable pageable) {
		Page<LiveDownloadJob> page;
		if (activeOnly) {
			page = jobRepository.findByStatusIn(ACTIVE_PIPELINE_STATUSES, pageable);
		} else if (status != null) {
			page = jobRepository.findByStatus(status, pageable);
		} else {
			page = jobRepository.findByStatusNot(LiveJobStatus.DELETED, pageable);
		}
		return page.map(RecordingJobMapper::toResponse);
	}

	@Transactional(readOnly = true)
	public RecordingJobResponse get(UUID id) {
		LiveDownloadJob job = findVisibleJob(id);
		return RecordingJobMapper.toResponse(job);
	}

	@Transactional
	public void delete(UUID id) {
		LiveDownloadJob job = jobRepository.findById(id)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + id));

		if (job.getStatus() == LiveJobStatus.DELETED) {
			log.info("Delete ignored (already DELETED) jobId={}", id);
			return;
		}

		if (!isDeletable(job)) {
			throw new InvalidRecordingStateException("Cannot delete job in status " + job.getStatus());
		}

		deleteFileQuietly(job.getTempFilePath());
		deleteFileQuietly(job.getFinalFilePath());

		job.setStatus(LiveJobStatus.DELETED);
		jobRepository.save(job);
		log.info("Deleted recording jobId={}", id);
	}

	@Transactional(readOnly = true)
	public RecordingFileDownload getDownload(UUID id) {
		LiveDownloadJob job = findVisibleJob(id);
		if (job.getStatus() != LiveJobStatus.COMPLETED) {
			throw new InvalidRecordingStateException("File download is only available for COMPLETED jobs");
		}
		if (job.getFinalFilePath() == null || job.getFinalFilePath().isBlank()) {
			throw new StorageException("Final recording file is not available");
		}

		Path expected = pathResolver.resolveMp4Path(job.getOutputBaseName());
		Path actual = pathResolver.toPath(job.getFinalFilePath());
		if (!actual.equals(expected)) {
			log.error("Final file path mismatch jobId={} expected={} actual={}", id, expected, actual);
			throw new StorageException("Final recording path is invalid");
		}
		if (!Files.exists(actual) || !Files.isRegularFile(actual)) {
			throw new StorageException("Final recording file not found on disk");
		}

		long contentLength;
		try {
			contentLength = Files.size(actual);
		} catch (IOException ex) {
			throw new StorageException("Unable to read final recording file", ex);
		}

		String filename = expected.getFileName().toString();
		Resource resource = new FileSystemResource(actual);
		return new RecordingFileDownload(resource, filename, contentLength);
	}

	private LiveDownloadJob findVisibleJob(UUID id) {
		LiveDownloadJob job = jobRepository.findById(id)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + id));
		if (job.getStatus() == LiveJobStatus.DELETED) {
			throw new RecordingNotFoundException("Recording job not found: " + id);
		}
		return job;
	}

	private void deleteFileQuietly(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) {
			return;
		}
		try {
			Path path = pathResolver.toPath(storedPath);
			boolean deleted = Files.deleteIfExists(path);
			if (deleted) {
				log.info("Deleted recording file {}", path.getFileName());
			}
		} catch (Exception ex) {
			log.warn("Failed to delete recording file path={}: {}", storedPath, ex.getMessage());
		}
	}

	private void applyProbeMetadata(LiveDownloadJob job, StreamProbeResult probe) {
		job.setVideoCodec(probe.getVideoCodec());
		job.setAudioCodec(probe.getAudioCodec());
		job.setWidth(probe.getWidth());
		job.setHeight(probe.getHeight());
		job.setFps(probe.getFps());
	}

	/**
	 * Terminal / pre-start jobs are deletable. RECORDING is deletable only when no FFmpeg
	 * process is registered (orphaned after a crash/race); STOPPING/REMUXING are not.
	 */
	private boolean isDeletable(LiveDownloadJob job) {
		LiveJobStatus status = job.getStatus();
		if (DELETABLE_STATUSES.contains(status)) {
			return true;
		}
		return status == LiveJobStatus.RECORDING && !ffmpegRecordingService.isRunning(job.getId());
	}

	private void failJob(LiveDownloadJob job, String message) {
		job.setStatus(LiveJobStatus.FAILED);
		job.setErrorMessage(truncate(UrlRedactor.redactInText(message), 2000));
		jobRepository.save(job);
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "...";
	}

	public record RecordingFileDownload(Resource resource, String filename, long contentLength) {
	}
}
