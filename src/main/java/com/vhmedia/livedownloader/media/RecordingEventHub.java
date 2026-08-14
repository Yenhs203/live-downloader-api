package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.dto.response.RecordingEventResponse;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe SSE fan-out for recording jobs.
 * <p>
 * UI progress events are forwarded promptly; DB persistence is handled separately
 * with throttling so FFmpeg progress ticks do not flood PostgreSQL.
 */
@Slf4j
@Component
public class RecordingEventHub {

	private static final Set<LiveJobStatus> TERMINAL_STATUSES = EnumSet.of(
			LiveJobStatus.COMPLETED,
			LiveJobStatus.FAILED,
			LiveJobStatus.INTERRUPTED,
			LiveJobStatus.DELETED
	);

	private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByJob = new ConcurrentHashMap<>();
	private final Map<UUID, RecordingProgress> lastProgressByJob = new ConcurrentHashMap<>();
	private final LiveDownloadJobRepository jobRepository;
	private final long sseTimeoutMs;

	public RecordingEventHub(LiveDownloadJobRepository jobRepository, MediaProperties mediaProperties) {
		this.jobRepository = jobRepository;
		this.sseTimeoutMs = Duration.ofSeconds(mediaProperties.getSseTimeoutSeconds()).toMillis();
	}

	public SseEmitter subscribe(UUID jobId) {
		SseEmitter emitter = new SseEmitter(sseTimeoutMs);
		emittersByJob.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(emitter);

		emitter.onCompletion(() -> removeEmitter(jobId, emitter));
		emitter.onTimeout(() -> {
			log.debug("SSE timeout jobId={}", jobId);
			removeEmitter(jobId, emitter);
			emitter.complete();
		});
		emitter.onError(ex -> {
			log.debug("SSE error jobId={}: {}", jobId, ex.toString());
			removeEmitter(jobId, emitter);
		});

		try {
			sendSnapshot(jobId, emitter);
		} catch (IOException ex) {
			removeEmitter(jobId, emitter);
			emitter.completeWithError(ex);
			return emitter;
		}

		return emitter;
	}

	@EventListener
	public void onProgress(RecordingProgressEvent event) {
		lastProgressByJob.put(event.jobId(), event.progress());
		broadcast(event.jobId(), RecordingSseEvents.PROGRESS, toProgressPayload(event.jobId(), LiveJobStatus.RECORDING, event.progress()));
	}

	@EventListener
	public void onStatusChanged(JobStatusChangedEvent event) {
		String eventName = switch (event.status()) {
			case RECORDING -> RecordingSseEvents.STARTED;
			case STOPPING -> RecordingSseEvents.STOPPING;
			case REMUXING -> RecordingSseEvents.REMUXING;
			case COMPLETED -> RecordingSseEvents.COMPLETED;
			case FAILED, INTERRUPTED -> RecordingSseEvents.FAILED;
			default -> RecordingSseEvents.forStatus(event.status());
		};

		RecordingProgress last = lastProgressByJob.get(event.jobId());
		RecordingEventResponse payload = RecordingEventResponse.builder()
				.jobId(event.jobId())
				.status(event.status())
				.durationMillis(firstNonNull(event.durationMillis(), last != null ? last.getOutTimeMs() : null))
				.downloadedBytes(firstNonNull(event.downloadedBytes(), last != null ? last.getTotalSize() : null))
				.fps(last != null ? last.getFps() : null)
				.speed(last != null ? last.getSpeed() : null)
				.bitrate(last != null ? last.getBitrate() : null)
				.build();

		broadcast(event.jobId(), eventName, payload);

		if (TERMINAL_STATUSES.contains(event.status())) {
			lastProgressByJob.remove(event.jobId());
			completeEmitters(event.jobId());
		}
	}

	@EventListener
	public void onFinished(RecordingFinishedEvent event) {
		// Keep last progress for remux/completed snapshots; status events drive SSE names.
		RecordingExitResult result = event.result();
		if (result.getDownloadedBytes() != null || result.getDurationMillis() != null) {
			RecordingProgress previous = lastProgressByJob.getOrDefault(result.getJobId(), RecordingProgress.builder().build());
			lastProgressByJob.put(result.getJobId(), RecordingProgress.builder()
					.outTimeMs(firstNonNull(result.getDurationMillis(), previous.getOutTimeMs()))
					.totalSize(firstNonNull(result.getDownloadedBytes(), previous.getTotalSize()))
					.fps(previous.getFps())
					.speed(previous.getSpeed())
					.bitrate(previous.getBitrate())
					.progress(previous.getProgress())
					.build());
		}
	}

	private void sendSnapshot(UUID jobId, SseEmitter emitter) throws IOException {
		LiveDownloadJob job = jobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}

		RecordingProgress last = lastProgressByJob.get(jobId);
		RecordingEventResponse payload = RecordingEventResponse.builder()
				.jobId(jobId)
				.status(job.getStatus())
				.durationMillis(firstNonNull(last != null ? last.getOutTimeMs() : null, job.getDurationMillis()))
				.downloadedBytes(firstNonNull(last != null ? last.getTotalSize() : null, job.getDownloadedBytes()))
				.fps(firstNonNull(last != null ? last.getFps() : null, job.getFps()))
				.speed(last != null ? last.getSpeed() : null)
				.bitrate(last != null ? last.getBitrate() : null)
				.build();

		emitter.send(SseEmitter.event()
				.name(RecordingSseEvents.forStatus(job.getStatus()))
				.data(payload, MediaType.APPLICATION_JSON));

		if (TERMINAL_STATUSES.contains(job.getStatus())) {
			removeEmitter(jobId, emitter);
			emitter.complete();
		}
	}

	private RecordingEventResponse toProgressPayload(UUID jobId, LiveJobStatus status, RecordingProgress progress) {
		return RecordingEventResponse.builder()
				.jobId(jobId)
				.status(status)
				.durationMillis(progress.getOutTimeMs())
				.downloadedBytes(progress.getTotalSize())
				.fps(progress.getFps())
				.speed(progress.getSpeed())
				.bitrate(progress.getBitrate())
				.build();
	}

	private void broadcast(UUID jobId, String eventName, RecordingEventResponse payload) {
		List<SseEmitter> emitters = emittersByJob.get(jobId);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}
		for (SseEmitter emitter : List.copyOf(emitters)) {
			try {
				emitter.send(SseEmitter.event()
						.name(eventName)
						.data(payload, MediaType.APPLICATION_JSON));
			} catch (Exception ex) {
				removeEmitter(jobId, emitter);
				try {
					emitter.complete();
				} catch (Exception ignored) {
					// already dead
				}
			}
		}
	}

	private void completeEmitters(UUID jobId) {
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByJob.remove(jobId);
		if (emitters == null) {
			return;
		}
		for (SseEmitter emitter : emitters) {
			try {
				emitter.complete();
			} catch (Exception ignored) {
				// ignore
			}
		}
	}

	private void removeEmitter(UUID jobId, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByJob.get(jobId);
		if (emitters == null) {
			return;
		}
		emitters.remove(emitter);
		if (emitters.isEmpty()) {
			emittersByJob.remove(jobId, emitters);
		}
	}

	private static <T> T firstNonNull(T first, T second) {
		return first != null ? first : second;
	}
}
