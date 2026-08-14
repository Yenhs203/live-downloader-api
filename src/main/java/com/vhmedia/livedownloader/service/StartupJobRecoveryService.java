package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recovers jobs left in in-memory process statuses after a crash/restart.
 * <p>
 * FFmpeg {@link Process} handles do not survive JVM restarts, so RECORDING/STOPPING
 * jobs cannot be continued. Stream URLs are never auto-restarted (tokens may have expired).
 * REMUXING jobs are completed only when a valid non-empty MP4 already exists on disk.
 */
@Slf4j
@Service
public class StartupJobRecoveryService {

	static final String INTERRUPTED_MESSAGE =
			"Interrupted by application restart; in-memory FFmpeg process was lost";

	private static final Set<LiveJobStatus> ACTIVE_STATUSES = EnumSet.of(
			LiveJobStatus.RECORDING,
			LiveJobStatus.STOPPING,
			LiveJobStatus.REMUXING
	);

	private final LiveDownloadJobRepository jobRepository;
	private final RecordingPathResolver pathResolver;
	private final TransactionTemplate transactionTemplate;

	public StartupJobRecoveryService(
			LiveDownloadJobRepository jobRepository,
			RecordingPathResolver pathResolver,
			PlatformTransactionManager transactionManager
	) {
		this.jobRepository = jobRepository;
		this.pathResolver = pathResolver;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		transactionTemplate.executeWithoutResult(status -> doRecover());
	}

	void doRecover() {
		List<LiveDownloadJob> stranded = jobRepository.findByStatusIn(ACTIVE_STATUSES);
		if (stranded.isEmpty()) {
			log.info("Startup recovery: no active jobs to recover");
			return;
		}

		int recordingInterrupted = 0;
		int stoppingInterrupted = 0;
		int remuxInterrupted = 0;
		int remuxCompleted = 0;
		Instant now = Instant.now();

		for (LiveDownloadJob job : stranded) {
			LiveJobStatus previous = job.getStatus();
			switch (previous) {
				case RECORDING -> {
					markInterrupted(job, now);
					recordingInterrupted++;
				}
				case STOPPING -> {
					markInterrupted(job, now);
					stoppingInterrupted++;
				}
				case REMUXING -> {
					if (tryCompleteFromExistingMp4(job, now)) {
						remuxCompleted++;
					} else {
						markInterrupted(job, now);
						remuxInterrupted++;
					}
				}
				default -> log.warn(
						"Startup recovery skipped unexpected status jobId={} status={}",
						job.getId(),
						previous
				);
			}
			jobRepository.save(job);
			log.info(
					"Startup recovery jobId={} {} -> {} base={}",
					job.getId(),
					previous,
					job.getStatus(),
					job.getOutputBaseName()
			);
		}

		log.info(
				"Startup recovery summary: total={} recordingInterrupted={} stoppingInterrupted={} remuxCompleted={} remuxInterrupted={}",
				stranded.size(),
				recordingInterrupted,
				stoppingInterrupted,
				remuxCompleted,
				remuxInterrupted
		);
	}

	private void markInterrupted(LiveDownloadJob job, Instant now) {
		job.setStatus(LiveJobStatus.INTERRUPTED);
		job.setErrorMessage(INTERRUPTED_MESSAGE);
		if (job.getStoppedAt() == null) {
			job.setStoppedAt(now);
		}
	}

	/**
	 * Completes a REMUXING job when a valid non-empty MP4 is already on disk.
	 * Never restarts remux or recording.
	 */
	private boolean tryCompleteFromExistingMp4(LiveDownloadJob job, Instant now) {
		Path mp4 = resolveExistingMp4(job);
		if (mp4 == null) {
			return false;
		}
		try {
			long size = Files.size(mp4);
			if (size <= 0) {
				return false;
			}
			job.setStatus(LiveJobStatus.COMPLETED);
			job.setFinalFilePath(mp4.toString());
			job.setDownloadedBytes(size);
			job.setCompletedAt(now);
			if (job.getStoppedAt() == null) {
				job.setStoppedAt(now);
			}
			job.setErrorMessage(null);
			return true;
		} catch (IOException ex) {
			log.warn(
					"Startup recovery could not read MP4 jobId={} path={}: {}",
					job.getId(),
					mp4.getFileName(),
					ex.getMessage()
			);
			return false;
		}
	}

	private Path resolveExistingMp4(LiveDownloadJob job) {
		UUID jobId = job.getId();
		try {
			Path expected = pathResolver.resolveMp4Path(job.getOutputBaseName());
			if (job.getFinalFilePath() != null && !job.getFinalFilePath().isBlank()) {
				Path stored = pathResolver.toPath(job.getFinalFilePath());
				if (!stored.equals(expected)) {
					log.warn(
							"Startup recovery MP4 path mismatch jobId={} expected={} stored={}",
							jobId,
							expected.getFileName(),
							stored.getFileName()
					);
					return null;
				}
			}
			if (!Files.exists(expected) || !Files.isRegularFile(expected)) {
				return null;
			}
			return expected;
		} catch (RuntimeException ex) {
			log.warn("Startup recovery invalid MP4 path jobId={}: {}", jobId, ex.getMessage());
			return null;
		}
	}
}
