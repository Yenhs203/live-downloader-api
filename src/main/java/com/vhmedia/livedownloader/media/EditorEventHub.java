package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.dto.response.EditorEventResponse;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.editor.SegmentMapper;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE hub for editor export jobs. Primary key is {@code exportId}
 * ({@code GET /api/v1/editor/exports/{exportId}/events}). Project-level
 * subscribe ({@code GET /api/v1/editor/projects/{id}/events}) receives the
 * same event names and payload for the project's latest export.
 * <p>
 * Progress ticks are forwarded promptly; DB persistence is throttled separately.
 */
@Slf4j
@Component
public class EditorEventHub {

	private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByExport = new ConcurrentHashMap<>();
	private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByProject = new ConcurrentHashMap<>();
	private final Map<UUID, RecordingProgress> lastProgressByExport = new ConcurrentHashMap<>();
	private final Map<UUID, RecordingProgress> lastProgressByProject = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastOutputDurationByExport = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastOutputDurationByProject = new ConcurrentHashMap<>();
	private final VideoProjectRepository projectRepository;
	private final VideoExportJobRepository exportJobRepository;
	private final VideoSegmentRepository segmentRepository;
	private final long sseTimeoutMs;

	public EditorEventHub(
			VideoProjectRepository projectRepository,
			VideoExportJobRepository exportJobRepository,
			VideoSegmentRepository segmentRepository,
			MediaProperties mediaProperties
	) {
		this.projectRepository = projectRepository;
		this.exportJobRepository = exportJobRepository;
		this.segmentRepository = segmentRepository;
		this.sseTimeoutMs = Duration.ofSeconds(mediaProperties.getSseTimeoutSeconds()).toMillis();
	}

	public SseEmitter subscribe(UUID projectId) {
		SseEmitter emitter = register(emittersByProject, projectId);
		try {
			sendProjectSnapshot(projectId, emitter);
		} catch (IOException ex) {
			removeEmitter(emittersByProject, projectId, emitter);
			emitter.completeWithError(ex);
			return emitter;
		}
		return emitter;
	}

	public SseEmitter subscribeExport(UUID exportId) {
		SseEmitter emitter = register(emittersByExport, exportId);
		try {
			sendExportSnapshot(exportId, emitter);
		} catch (IOException ex) {
			removeEmitter(emittersByExport, exportId, emitter);
			emitter.completeWithError(ex);
			return emitter;
		}
		return emitter;
	}

	@EventListener
	public void onProgress(EditorProgressEvent event) {
		lastProgressByProject.put(event.projectId(), event.progress());
		rememberOutputDuration(event.projectId(), event.exportJobId(), event.totalMillis());
		if (event.exportJobId() != null) {
			lastProgressByExport.put(event.exportJobId(), event.progress());
		}
		VideoExportJob job = resolveJob(event.exportJobId(), event.projectId());
		UUID exportId = job != null ? job.getId() : event.exportJobId();
		VideoProject project = projectRepository.findById(event.projectId()).orElse(null);
		EditorEventResponse payload = toPayload(
				exportId,
				event.projectId(),
				ExportStatus.RENDERING,
				event.progress(),
				resolveOutputDurationMillis(event.projectId(), exportId, project, event.totalMillis()),
				event.progress() != null ? event.progress().getOutTimeMs() : null
		);
		broadcast(exportId, event.projectId(), EditorSseEvents.PROGRESS, payload);
	}

	@EventListener
	public void onStatusChanged(EditorStatusChangedEvent event) {
		RecordingProgress last = event.exportJobId() != null
				? lastProgressByExport.getOrDefault(event.exportJobId(), lastProgressByProject.get(event.projectId()))
				: lastProgressByProject.get(event.projectId());
		VideoProject project = projectRepository.findById(event.projectId()).orElse(null);
		Long duration = resolveOutputDurationMillis(event.projectId(), event.exportJobId(), project, null);
		Long processed = firstNonNull(event.processedMillis(), last != null ? last.getOutTimeMs() : null);
		EditorEventResponse payload = toPayload(
				event.exportJobId(),
				event.projectId(),
				event.status(),
				last,
				duration,
				processed
		);
		broadcast(event.exportJobId(), event.projectId(), EditorSseEvents.forStatus(event.status()), payload);
		if (event.status() != null && event.status().isTerminal()) {
			if (event.exportJobId() != null) {
				lastProgressByExport.remove(event.exportJobId());
				lastOutputDurationByExport.remove(event.exportJobId());
				completeEmitters(emittersByExport, event.exportJobId());
			}
			lastProgressByProject.remove(event.projectId());
			lastOutputDurationByProject.remove(event.projectId());
			completeEmitters(emittersByProject, event.projectId());
		}
	}

	private void sendProjectSnapshot(UUID projectId, SseEmitter emitter) throws IOException {
		VideoProject project = projectRepository.findById(projectId).orElse(null);
		if (project == null) {
			return;
		}
		VideoExportJob job = exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
		sendSnapshot(emitter, project, job);
	}

	private void sendExportSnapshot(UUID exportId, SseEmitter emitter) throws IOException {
		VideoExportJob job = exportJobRepository.findById(exportId).orElse(null);
		if (job == null) {
			return;
		}
		VideoProject project = projectRepository.findById(job.getProjectId()).orElse(null);
		sendSnapshot(emitter, project, job);
	}

	private void sendSnapshot(SseEmitter emitter, VideoProject project, VideoExportJob job) throws IOException {
		UUID projectId = project != null ? project.getId() : (job != null ? job.getProjectId() : null);
		UUID exportId = job != null ? job.getId() : null;
		ExportStatus exportStatus = job != null ? job.getStatus() : null;
		RecordingProgress last = firstNonNull(
				exportId != null ? lastProgressByExport.get(exportId) : null,
				projectId != null ? lastProgressByProject.get(projectId) : null
		);
		Long processed = last != null
				? last.getOutTimeMs()
				: (job != null ? job.getProgressMillis() : null);
		Long duration = resolveOutputDurationMillis(projectId, exportId, project, null);
		Double percent = percent(processed, duration);
		if (percent == null && job != null) {
			percent = job.getProgressPercent();
		}
		EditorEventResponse payload = EditorEventResponse.builder()
				.exportId(exportId)
				.projectId(projectId)
				.status(exportStatus)
				.processedMillis(processed)
				.durationMillis(duration)
				.progressPercent(percent)
				.fps(last != null ? last.getFps() : null)
				.speed(last != null ? FfmpegProgressParser.parseSpeed(last.getSpeed()) : null)
				.build();
		emitter.send(SseEmitter.event()
				.name(EditorSseEvents.forStatus(exportStatus))
				.data(payload, MediaType.APPLICATION_JSON));
		if (exportStatus != null && exportStatus.isTerminal()) {
			if (exportId != null) {
				removeEmitter(emittersByExport, exportId, emitter);
			}
			if (projectId != null) {
				removeEmitter(emittersByProject, projectId, emitter);
			}
			emitter.complete();
		}
	}

	private EditorEventResponse toPayload(
			UUID exportId,
			UUID projectId,
			ExportStatus status,
			RecordingProgress progress,
			Long durationMillis,
			Long processedMillis
	) {
		return EditorEventResponse.builder()
				.exportId(exportId)
				.projectId(projectId)
				.status(status)
				.processedMillis(processedMillis)
				.durationMillis(durationMillis)
				.progressPercent(percent(processedMillis, durationMillis))
				.fps(progress != null ? progress.getFps() : null)
				.speed(progress != null ? FfmpegProgressParser.parseSpeed(progress.getSpeed()) : null)
				.build();
	}

	private void broadcast(UUID exportId, UUID projectId, String eventName, EditorEventResponse payload) {
		if (exportId != null) {
			sendTo(emittersByExport, exportId, eventName, payload);
		}
		if (projectId != null) {
			sendTo(emittersByProject, projectId, eventName, payload);
		}
	}

	private void sendTo(
			Map<UUID, CopyOnWriteArrayList<SseEmitter>> map,
			UUID key,
			String eventName,
			EditorEventResponse payload
	) {
		List<SseEmitter> emitters = map.get(key);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}
		for (SseEmitter emitter : List.copyOf(emitters)) {
			try {
				emitter.send(SseEmitter.event()
						.name(eventName)
						.data(payload, MediaType.APPLICATION_JSON));
			} catch (Exception ex) {
				removeEmitter(map, key, emitter);
				try {
					emitter.complete();
				} catch (Exception ignored) {
					// already dead
				}
			}
		}
	}

	private SseEmitter register(Map<UUID, CopyOnWriteArrayList<SseEmitter>> map, UUID key) {
		SseEmitter emitter = new SseEmitter(sseTimeoutMs);
		map.computeIfAbsent(key, id -> new CopyOnWriteArrayList<>()).add(emitter);
		emitter.onCompletion(() -> removeEmitter(map, key, emitter));
		emitter.onTimeout(() -> {
			log.debug("Editor SSE timeout key={}", key);
			removeEmitter(map, key, emitter);
			emitter.complete();
		});
		emitter.onError(ex -> {
			log.debug("Editor SSE error key={}: {}", key, ex.toString());
			removeEmitter(map, key, emitter);
		});
		return emitter;
	}

	private void completeEmitters(Map<UUID, CopyOnWriteArrayList<SseEmitter>> map, UUID key) {
		CopyOnWriteArrayList<SseEmitter> emitters = map.remove(key);
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

	private void removeEmitter(Map<UUID, CopyOnWriteArrayList<SseEmitter>> map, UUID key, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> emitters = map.get(key);
		if (emitters == null) {
			return;
		}
		emitters.remove(emitter);
		if (emitters.isEmpty()) {
			map.remove(key, emitters);
		}
	}

	/**
	 * SSE / persist denominator is editor output length, not probed source duration.
	 * Example: source 27.167s trimmed to 25.000s → 100% at 25s.
	 */
	Long resolveOutputDurationMillis(UUID projectId, UUID exportId, VideoProject project, Long hintedTotalMillis) {
		if (hintedTotalMillis != null && hintedTotalMillis > 0L) {
			return hintedTotalMillis;
		}
		Long cached = firstNonNull(
				exportId != null ? lastOutputDurationByExport.get(exportId) : null,
				projectId != null ? lastOutputDurationByProject.get(projectId) : null
		);
		if (cached != null && cached > 0L) {
			return cached;
		}
		if (projectId != null) {
			List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
			if (rows != null && !rows.isEmpty()) {
				long output = EditorTimelineDurations.outputDurationMillis(SegmentMapper.toDomain(rows));
				if (output > 0L) {
					return output;
				}
			}
		}
		return project != null ? project.getDurationMillis() : null;
	}

	private void rememberOutputDuration(UUID projectId, UUID exportId, Long outputDurationMillis) {
		if (outputDurationMillis == null || outputDurationMillis <= 0L) {
			return;
		}
		if (projectId != null) {
			lastOutputDurationByProject.put(projectId, outputDurationMillis);
		}
		if (exportId != null) {
			lastOutputDurationByExport.put(exportId, outputDurationMillis);
		}
	}

	private VideoExportJob resolveJob(UUID exportJobId, UUID projectId) {
		if (exportJobId != null) {
			return exportJobRepository.findById(exportJobId).orElse(null);
		}
		if (projectId == null) {
			return null;
		}
		return exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
	}

	static Double percent(Long processed, Long total) {
		if (processed == null || total == null || total <= 0) {
			return null;
		}
		return Math.min(100.0d, (processed * 100.0d) / total);
	}

	private static <T> T firstNonNull(T first, T second) {
		return first != null ? first : second;
	}
}
