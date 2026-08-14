package com.vhmedia.livedownloader.config;

import com.vhmedia.livedownloader.media.FfmpegRecordingService;
import com.vhmedia.livedownloader.media.RecordingProcessRegistry;
import com.vhmedia.livedownloader.media.RunningRecording;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * On JVM/Spring shutdown, requests graceful FFmpeg stop for all live recordings
 * and waits briefly so TS files are more likely to be finalized before process kill.
 * <p>
 * Remux may not finish within the shutdown window; startup recovery then marks
 * stranded jobs {@code INTERRUPTED} (or {@code COMPLETED} if an MP4 already exists).
 */
@Slf4j
@Component
public class RecordingShutdownCoordinator {

	private final RecordingProcessRegistry registry;
	private final FfmpegRecordingService ffmpegRecordingService;
	private final MediaProperties mediaProperties;

	public RecordingShutdownCoordinator(
			RecordingProcessRegistry registry,
			FfmpegRecordingService ffmpegRecordingService,
			MediaProperties mediaProperties
	) {
		this.registry = registry;
		this.ffmpegRecordingService = ffmpegRecordingService;
		this.mediaProperties = mediaProperties;
	}

	@Order(0)
	@EventListener(ContextClosedEvent.class)
	public void onContextClosed() {
		List<UUID> activeIds = new ArrayList<>();
		for (RunningRecording running : registry.snapshot()) {
			activeIds.add(running.getJobId());
		}
		if (activeIds.isEmpty()) {
			log.info("Shutdown: no active FFmpeg recordings to stop");
			return;
		}

		log.warn("Shutdown: requesting graceful stop for {} active recording(s)", activeIds.size());
		for (UUID jobId : activeIds) {
			try {
				ffmpegRecordingService.requestGracefulStop(jobId);
			} catch (RuntimeException ex) {
				log.warn("Shutdown: failed to signal stop for jobId={}: {}", jobId, ex.getMessage());
			}
		}

		long waitSeconds = Math.max(5L, mediaProperties.getStopTimeoutSeconds() + 3L);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
		while (registry.size() > 0 && System.nanoTime() < deadline) {
			try {
				TimeUnit.MILLISECONDS.sleep(250L);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		int remaining = registry.size();
		if (remaining > 0) {
			log.warn(
					"Shutdown: {} recording(s) still registered after {}s; ProcessDestroyOnExit / destroy will apply",
					remaining,
					waitSeconds
			);
		} else {
			log.info("Shutdown: all FFmpeg recording processes exited within {}s", waitSeconds);
		}
	}
}
