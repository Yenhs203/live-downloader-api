package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.media.RecordingProgress;
import com.vhmedia.livedownloader.media.RecordingProgressEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists recording progress to PostgreSQL on a throttled interval.
 * SSE UI updates remain unthrottled in {@link com.vhmedia.livedownloader.media.RecordingEventHub}.
 */
@Slf4j
@Service
public class RecordingProgressPersistenceService {

	private final LiveDownloadJobRepository jobRepository;
	private final long persistIntervalMs;
	private final Map<UUID, Long> lastPersistEpochMs = new ConcurrentHashMap<>();

	public RecordingProgressPersistenceService(
			LiveDownloadJobRepository jobRepository,
			MediaProperties mediaProperties
	) {
		this.jobRepository = jobRepository;
		this.persistIntervalMs = mediaProperties.getProgressPersistIntervalSeconds() * 1000L;
	}

	@EventListener
	@Transactional
	public void onProgress(RecordingProgressEvent event) {
		UUID jobId = event.jobId();
		long now = System.currentTimeMillis();
		boolean[] shouldPersist = {false};
		lastPersistEpochMs.compute(jobId, (id, previous) -> {
			if (previous != null && (now - previous) < persistIntervalMs) {
				return previous;
			}
			shouldPersist[0] = true;
			return now;
		});
		if (!shouldPersist[0]) {
			return;
		}

		LiveDownloadJob job = jobRepository.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != LiveJobStatus.RECORDING) {
			return;
		}

		RecordingProgress progress = event.progress();
		boolean dirty = false;
		if (progress.getTotalSize() != null) {
			job.setDownloadedBytes(progress.getTotalSize());
			dirty = true;
		}
		if (progress.getOutTimeMs() != null) {
			job.setDurationMillis(progress.getOutTimeMs());
			dirty = true;
		}
		if (progress.getFps() != null) {
			job.setFps(progress.getFps());
			dirty = true;
		}
		if (dirty) {
			job.setUpdatedAt(Instant.now());
			jobRepository.save(job);
			log.debug(
					"Persisted throttled progress jobId={} bytes={} durationMs={}",
					jobId,
					job.getDownloadedBytes(),
					job.getDurationMillis()
			);
		}
	}

	public void clear(UUID jobId) {
		lastPersistEpochMs.remove(jobId);
	}
}
