package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.media.EditorProgressEvent;
import com.vhmedia.livedownloader.media.EditorStatusChangedEvent;
import com.vhmedia.livedownloader.media.RecordingProgress;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists editor export progress to PostgreSQL on a throttled interval.
 * The same interval is used for an INFO progress summary (never per FFmpeg frame).
 * SSE UI updates remain unthrottled in {@link com.vhmedia.livedownloader.media.EditorEventHub}.
 */
@Slf4j
@Service
public class EditorExportProgressPersistenceService {

	private final VideoExportJobRepository exportJobRepository;
	private final long persistIntervalMs;
	private final Map<UUID, Long> lastPersistEpochMs = new ConcurrentHashMap<>();

	public EditorExportProgressPersistenceService(
			VideoExportJobRepository exportJobRepository,
			MediaProperties mediaProperties
	) {
		this.exportJobRepository = exportJobRepository;
		this.persistIntervalMs = mediaProperties.getProgressPersistIntervalSeconds() * 1000L;
	}

	@EventListener
	@Transactional
	public void onProgress(EditorProgressEvent event) {
		UUID exportId = event.exportJobId();
		if (exportId == null) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean[] shouldPersist = {false};
		lastPersistEpochMs.compute(exportId, (id, previous) -> {
			if (previous != null && (now - previous) < persistIntervalMs) {
				return previous;
			}
			shouldPersist[0] = true;
			return now;
		});
		if (!shouldPersist[0]) {
			return;
		}

		VideoExportJob job = exportJobRepository.findById(exportId).orElse(null);
		if (job == null || job.getStatus() != ExportStatus.RENDERING) {
			return;
		}

		RecordingProgress progress = event.progress();
		if (progress == null || progress.getOutTimeMs() == null) {
			return;
		}
		job.setProgressMillis(progress.getOutTimeMs());
		job.setProgressPercent(percent(progress.getOutTimeMs(), event.totalMillis()));
		exportJobRepository.save(job);
		log.info(
				"Editor export progress projectId={} exportId={} processedMs={} durationMs={} percent={} fps={} speed={}",
				event.projectId(),
				exportId,
				job.getProgressMillis(),
				event.totalMillis(),
				job.getProgressPercent(),
				progress.getFps(),
				progress.getSpeed()
		);
	}

	@EventListener
	public void onStatusChanged(EditorStatusChangedEvent event) {
		if (event.exportJobId() != null && event.status() != null && event.status().isTerminal()) {
			lastPersistEpochMs.remove(event.exportJobId());
		}
	}

	private static Double percent(Long processed, Long total) {
		if (processed == null || total == null || total <= 0) {
			return null;
		}
		return Math.min(100.0d, (processed * 100.0d) / total);
	}
}
