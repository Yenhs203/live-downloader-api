package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.EditorTaskExecutor;
import com.vhmedia.livedownloader.dto.request.UpdateEditorExportRequest;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.editor.EditorExportFps;
import com.vhmedia.livedownloader.editor.EditorExportPlan;
import com.vhmedia.livedownloader.editor.EditorExportPlanner;
import com.vhmedia.livedownloader.editor.EditorExportSettings;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.EditorSegmentValidator;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.editor.SegmentMapper;
import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.exception.ConcurrentEditorLimitException;
import com.vhmedia.livedownloader.exception.EditorAssetNotFoundException;
import com.vhmedia.livedownloader.exception.EditorExportNotFoundException;
import com.vhmedia.livedownloader.exception.EditorProjectNotFoundException;
import com.vhmedia.livedownloader.exception.EditorRenderCancelledException;
import com.vhmedia.livedownloader.exception.EditorRenderException;
import com.vhmedia.livedownloader.exception.EditorStorageException;
import com.vhmedia.livedownloader.exception.ExportAlreadyRunningException;
import com.vhmedia.livedownloader.exception.ExportNotReadyException;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import com.vhmedia.livedownloader.exception.InvalidEditorStateException;
import com.vhmedia.livedownloader.media.EditorStatusChangedEvent;
import com.vhmedia.livedownloader.media.FfmpegVisualReorderService;
import com.vhmedia.livedownloader.repository.VideoAssetRepository;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import com.vhmedia.livedownloader.util.EditorPathResolver;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import com.vhmedia.livedownloader.util.UrlRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VideoEditorRenderService {

	private static final List<ExportStatus> ACTIVE_EXPORT = List.of(
			ExportStatus.CREATED,
			ExportStatus.PREPARING,
			ExportStatus.RENDERING,
			ExportStatus.FINALIZING
	);

	private final VideoProjectRepository projectRepository;
	private final VideoSegmentRepository segmentRepository;
	private final VideoAssetRepository assetRepository;
	private final VideoExportJobRepository exportJobRepository;
	private final VideoEditorService videoEditorService;
	private final EditorSegmentValidator segmentValidator;
	private final EditorExportPlanner exportPlanner;
	private final FfmpegVisualReorderService visualReorderService;
	private final EditorPathResolver editorPathResolver;
	private final RecordingPathResolver recordingPathResolver;
	private final EditorProperties editorProperties;
	private final EditorTaskExecutor editorTaskExecutor;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public VideoEditorRenderService(
			VideoProjectRepository projectRepository,
			VideoSegmentRepository segmentRepository,
			VideoAssetRepository assetRepository,
			VideoExportJobRepository exportJobRepository,
			VideoEditorService videoEditorService,
			EditorSegmentValidator segmentValidator,
			EditorExportPlanner exportPlanner,
			FfmpegVisualReorderService visualReorderService,
			EditorPathResolver editorPathResolver,
			RecordingPathResolver recordingPathResolver,
			EditorProperties editorProperties,
			EditorTaskExecutor editorTaskExecutor,
			ApplicationEventPublisher eventPublisher,
			PlatformTransactionManager transactionManager
	) {
		this.projectRepository = projectRepository;
		this.segmentRepository = segmentRepository;
		this.assetRepository = assetRepository;
		this.exportJobRepository = exportJobRepository;
		this.videoEditorService = videoEditorService;
		this.segmentValidator = segmentValidator;
		this.exportPlanner = exportPlanner;
		this.visualReorderService = visualReorderService;
		this.editorPathResolver = editorPathResolver;
		this.recordingPathResolver = recordingPathResolver;
		this.editorProperties = editorProperties;
		this.editorTaskExecutor = editorTaskExecutor;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public EditorProjectResponse startRender(UUID projectId) {
		return startRender(projectId, null);
	}

	public EditorProjectResponse startRender(UUID projectId, UpdateEditorExportRequest exportRequest) {
		recordingPathResolver.assertWritableAndHasFreeSpace();
		editorPathResolver.assertWritableAndHasFreeSpace();
		visualReorderService.acquireExportSlot();
		boolean slotTransferred = false;
		try {
			VideoExportJob job = transactionTemplate.execute(status -> {
				VideoProject locked = projectRepository.findByIdForUpdate(projectId)
						.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
				if (locked.getStatus() == ProjectStatus.DELETED) {
					throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
				}
				if (locked.getStatus() != ProjectStatus.READY) {
					throw new InvalidEditorStateException("Cannot export project in status " + locked.getStatus());
				}
				if (exportJobRepository.existsByProjectIdAndStatusIn(projectId, ACTIVE_EXPORT)) {
					throw new ExportAlreadyRunningException("An export job is already in progress");
				}
				List<EditorSegment> segments = SegmentMapper.toDomain(
						segmentRepository.findByProjectIdOrderByPositionAsc(projectId)
				);
				if (segments.isEmpty()) {
					throw new InvalidEditorSegmentsException("Segments must be defined before render");
				}
				if (locked.getDurationMillis() == null || locked.getDurationMillis() <= 0) {
					throw new InvalidEditorSegmentsException("Source duration is unknown");
				}
				segmentValidator.normalize(segments, locked.getDurationMillis(), knownAssetIds(projectId));
				long outputDurationMillis = EditorTimelineDurations.outputDurationMillis(segments);
				EditorTimelineDurations.assertFitsLockedAudio(
						locked.isHasAudio(),
						locked.getDurationMillis(),
						segments
				);
				if (exportRequest != null) {
					EditorExportSettings merged = videoEditorService.mergeExport(
							VideoEditorService.settingsOf(locked),
							exportRequest
					);
					VideoEditorService.applyExportSettings(locked, merged);
					projectRepository.save(locked);
				}
				EditorExportSettings settings = VideoEditorService.settingsOf(locked);
				Integer requestedFps = settings.fps().isOriginal() || settings.fps().fps() == null
						? null
						: settings.fps().fps().intValue();
				VideoExportJob created = VideoExportJob.builder()
						.id(UUID.randomUUID())
						.projectId(projectId)
						.status(ExportStatus.PREPARING)
						.fpsPreset(settings.fps().apiValue())
						.requestedFps(requestedFps)
						.resolution(settings.resolution().apiValue())
						.videoCodec(settings.codec().apiValue())
						.quality(settings.quality().apiValue())
						.keepOriginalAudio(true)
						.startedAt(Instant.now())
						.build();
				VideoExportJob saved = exportJobRepository.save(created);
				EditorExportPlan planned = exportPlanner.plan(
						locked.getWidth(),
						locked.getHeight(),
						locked.getFps(),
						settings
				);
				log.info(
						"Editor export queued projectId={} exportId={} status={} sourceDurationMs={} outputDurationMs={} inputWidth={} inputHeight={} inputFps={} videoCodec={} audioCodec={} hasAudio={} exportFps={} exportResolution={} exportCodec={} quality={} keepOriginalAudio=true outputWidth={} outputHeight={} outputFps={}",
						projectId,
						saved.getId(),
						saved.getStatus(),
						locked.getDurationMillis(),
						outputDurationMillis,
						locked.getWidth(),
						locked.getHeight(),
						locked.getFps(),
						locked.getVideoCodec(),
						locked.getAudioCodec(),
						locked.isHasAudio(),
						settings.fps().apiValue(),
						settings.resolution().apiValue(),
						settings.codec().apiValue(),
						settings.quality().apiValue(),
						planned.outputWidth(),
						planned.outputHeight(),
						planned.outputFps()
				);
				return saved;
			});

			if (job == null) {
				throw new EditorRenderException("Unable to start editor export");
			}

			eventPublisher.publishEvent(new EditorStatusChangedEvent(projectId, job.getId(), ExportStatus.PREPARING));

			try {
				editorTaskExecutor.execute(() -> runRender(projectId, job.getId()));
				slotTransferred = true;
			} catch (RejectedExecutionException ex) {
				markFailed(job.getId(), "Maximum concurrent editor exports exceeded");
				throw new ConcurrentEditorLimitException(
						"Maximum concurrent editor exports exceeded (" + editorProperties.getMaxConcurrentExports() + ")"
				);
			} catch (RuntimeException ex) {
				markFailed(job.getId(), "Failed to schedule editor export");
				throw new EditorRenderException("Failed to schedule editor export", ex);
			}

			return videoEditorService.get(projectId);
		} finally {
			if (!slotTransferred) {
				visualReorderService.releaseExportSlot();
			}
		}
	}

	public EditorProjectResponse requestCancel(UUID projectId) {
		return requestCancel(projectId, null);
	}

	public EditorProjectResponse requestCancelExport(UUID exportId) {
		VideoExportJob existing = exportJobRepository.findById(exportId)
				.orElseThrow(() -> new EditorExportNotFoundException("Editor export not found: " + exportId));
		return requestCancel(existing.getProjectId(), exportId);
	}

	private EditorProjectResponse requestCancel(UUID projectId, UUID exportId) {
		VideoExportJob job = transactionTemplate.execute(status -> {
			VideoProject locked = projectRepository.findByIdForUpdate(projectId)
					.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
			if (locked.getStatus() == ProjectStatus.DELETED) {
				throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
			}
			VideoExportJob target;
			if (exportId != null) {
				target = exportJobRepository.findByIdForUpdate(exportId)
						.orElseThrow(() -> new EditorExportNotFoundException("Editor export not found: " + exportId));
				if (!projectId.equals(target.getProjectId())) {
					throw new EditorExportNotFoundException("Editor export not found: " + exportId);
				}
			} else {
				target = exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
						.orElseThrow(() -> new ExportNotReadyException("No export job to cancel"));
			}
			if (target.isCancelRequested()) {
				return target;
			}
			if (!target.getStatus().isActive()) {
				throw new ExportNotReadyException("Cancel is only available while export is running");
			}
			target.setCancelRequested(true);
			return exportJobRepository.save(target);
		});
		if (job == null) {
			throw new EditorRenderException("Unable to cancel editor export");
		}
		boolean initiated = visualReorderService.requestCancel(projectId, job.getId());
		if (!initiated && !visualReorderService.isRunning(projectId)) {
			log.info("Cancel requested but editor process already gone projectId={} exportJobId={}", projectId, job.getId());
		}
		return videoEditorService.get(projectId);
	}

	private void runRender(UUID projectId, UUID jobId) {
		try {
			doRunRender(projectId, jobId);
		} finally {
			visualReorderService.releaseExportSlot();
		}
	}

	private void doRunRender(UUID projectId, UUID jobId) {
		VideoExportJob job = exportJobRepository.findById(jobId).orElse(null);
		VideoProject project = projectRepository.findById(projectId).orElse(null);
		if (job == null || project == null || !job.getStatus().isActive()) {
			log.warn("Skip editor export projectId={} jobId={} missing or not active", projectId, jobId);
			return;
		}

		Path tmp = null;
		try {
			if (job.isCancelRequested() || visualReorderService.isCancelRequested(projectId)) {
				markCancelled(jobId);
				return;
			}
			markStatus(jobId, ExportStatus.RENDERING);
			eventPublisher.publishEvent(new EditorStatusChangedEvent(projectId, jobId, ExportStatus.RENDERING));

			VideoAsset sourceAsset = assetRepository.findByProjectIdAndPrimarySourceTrue(projectId)
					.orElseThrow(() -> new EditorAssetNotFoundException("Editor source asset is missing"));
			Path source = editorPathResolver.toPath(sourceAsset.getStoragePath());
			editorPathResolver.assertProjectSourceFile(projectId, source);
			if (!Files.isRegularFile(source)) {
				throw new EditorStorageException("Editor source file not found on disk");
			}
			tmp = editorPathResolver.tempExportFile(projectId, jobId);
			Path output = editorPathResolver.exportFile(projectId, jobId);
			List<EditorSegment> segments = segmentValidator.normalize(
					SegmentMapper.toDomain(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)),
					project.getDurationMillis(),
					knownAssetIds(projectId)
			);
			long outputDurationMillis = EditorTimelineDurations.outputDurationMillis(segments);
			EditorTimelineDurations.assertFitsLockedAudio(
					project.isHasAudio(),
					project.getDurationMillis(),
					segments
			);
			EditorExportSettings settings = new EditorExportSettings(
					EditorExportFps.fromApi(job.getFpsPreset()),
					com.vhmedia.livedownloader.editor.EditorExportResolution.fromApi(job.getResolution()),
					com.vhmedia.livedownloader.editor.EditorExportCodec.fromApi(job.getVideoCodec()),
					com.vhmedia.livedownloader.editor.EditorExportQuality.fromApi(job.getQuality()),
					true
			).normalized();
			EditorExportPlan plan = exportPlanner.plan(project.getWidth(), project.getHeight(), project.getFps(), settings);
			if (exportJobRepository.findById(jobId).map(VideoExportJob::isCancelRequested).orElse(false)
					|| visualReorderService.isCancelRequested(projectId)) {
				markCancelled(jobId);
				return;
			}
			long size = visualReorderService.renderWithAcquiredSlot(
					projectId,
					jobId,
					source,
					tmp,
					segments,
					outputDurationMillis,
					project.getDurationMillis() == null ? outputDurationMillis : project.getDurationMillis(),
					project.isHasAudio(),
					project.getAudioCodec(),
					plan,
					imageAssetPaths(projectId, segments)
			);
			if (visualReorderService.isCancelRequested(projectId) || exportJobRepository.findById(jobId)
					.map(VideoExportJob::isCancelRequested)
					.orElse(false)) {
				markCancelled(jobId);
				return;
			}
			markStatus(jobId, ExportStatus.FINALIZING);
			eventPublisher.publishEvent(new EditorStatusChangedEvent(projectId, jobId, ExportStatus.FINALIZING));
			editorPathResolver.moveReplace(tmp, output);
			markCompleted(jobId, output, size);
		} catch (EditorRenderCancelledException ex) {
			markCancelled(jobId);
		} catch (ConcurrentEditorLimitException ex) {
			markFailed(jobId, ex.getMessage());
		} catch (RuntimeException ex) {
			if (exportJobRepository.findById(jobId).map(VideoExportJob::isCancelRequested).orElse(false)) {
				markCancelled(jobId);
				return;
			}
			log.error("Editor export failed projectId={} jobId={}", projectId, jobId, ex);
			markFailed(jobId, "Editor export failed");
		} finally {
			if (editorProperties.isDeleteTempAfterExport()) {
				editorPathResolver.deleteQuietly(tmp);
				try {
					editorPathResolver.cleanupTemp(projectId);
				} catch (RuntimeException ex) {
					log.warn("Failed to cleanup editor temp files projectId={}: {}", projectId, ex.getMessage());
				}
			}
		}
	}

	private void markStatus(UUID jobId, ExportStatus toStatus) {
		transactionTemplate.executeWithoutResult(status -> {
			VideoExportJob job = exportJobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || !job.getStatus().isActive()) {
				return;
			}
			ExportStatus from = job.getStatus();
			job.setStatus(toStatus);
			exportJobRepository.save(job);
			log.info(
					"Editor export status transition projectId={} exportId={} from={} to={}",
					job.getProjectId(),
					jobId,
					from,
					toStatus
			);
		});
	}

	private void markCompleted(UUID jobId, Path output, long size) {
		VideoExportJob completed = transactionTemplate.execute(status -> {
			VideoExportJob job = exportJobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || !job.getStatus().isActive()) {
				return null;
			}
			ExportStatus from = job.getStatus();
			job.setStatus(ExportStatus.COMPLETED);
			job.setOutputFilePath(editorPathResolver.toStoredPath(output));
			job.setOutputBytes(size);
			job.setCompletedAt(Instant.now());
			job.setErrorMessage(null);
			job.setProgressPercent(100.0d);
			VideoExportJob saved = exportJobRepository.save(job);
			log.info(
					"Editor export status transition projectId={} exportId={} from={} to={} sizeBytes={}",
					saved.getProjectId(),
					jobId,
					from,
					ExportStatus.COMPLETED,
					size
			);
			return saved;
		});
		if (completed == null) {
			return;
		}
		eventPublisher.publishEvent(new EditorStatusChangedEvent(
				completed.getProjectId(),
				completed.getId(),
				ExportStatus.COMPLETED,
				null,
				size
		));
	}

	private void markFailed(UUID jobId, String message) {
		String safe = truncate(UrlRedactor.redactInText(message), 2000);
		VideoExportJob failed = transactionTemplate.execute(status -> {
			VideoExportJob job = exportJobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || job.getStatus().isTerminal()) {
				return null;
			}
			ExportStatus from = job.getStatus();
			job.setStatus(ExportStatus.FAILED);
			job.setErrorMessage(safe);
			job.setCompletedAt(Instant.now());
			VideoExportJob saved = exportJobRepository.save(job);
			log.warn(
					"Editor export status transition projectId={} exportId={} from={} to={}",
					saved.getProjectId(),
					jobId,
					from,
					ExportStatus.FAILED
			);
			return saved;
		});
		if (failed == null) {
			return;
		}
		eventPublisher.publishEvent(new EditorStatusChangedEvent(failed.getProjectId(), failed.getId(), ExportStatus.FAILED));
	}

	private void markCancelled(UUID jobId) {
		VideoExportJob cancelled = transactionTemplate.execute(status -> {
			VideoExportJob job = exportJobRepository.findByIdForUpdate(jobId).orElse(null);
			if (job == null || job.getStatus().isTerminal()) {
				return null;
			}
			ExportStatus from = job.getStatus();
			job.setStatus(ExportStatus.CANCELLED);
			job.setCancelRequested(true);
			job.setErrorMessage("Export cancelled");
			job.setCompletedAt(Instant.now());
			VideoExportJob saved = exportJobRepository.save(job);
			log.info(
					"Editor export status transition projectId={} exportId={} from={} to={}",
					saved.getProjectId(),
					jobId,
					from,
					ExportStatus.CANCELLED
			);
			return saved;
		});
		if (cancelled == null) {
			return;
		}
		eventPublisher.publishEvent(new EditorStatusChangedEvent(
				cancelled.getProjectId(),
				cancelled.getId(),
				ExportStatus.CANCELLED
		));
	}

	private Set<UUID> knownAssetIds(UUID projectId) {
		return assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
				.map(VideoAsset::getId)
				.collect(Collectors.toUnmodifiableSet());
	}

	private Map<UUID, Path> imageAssetPaths(UUID projectId, List<EditorSegment> segments) {
		Map<UUID, Path> paths = new LinkedHashMap<>();
		for (EditorSegment segment : segments) {
			if (!segment.isImage()) {
				continue;
			}
			VideoAsset asset = assetRepository.findByIdAndProjectId(segment.assetId(), projectId)
					.orElseThrow(() -> new InvalidEditorSegmentsException(
							"IMAGE assetId does not belong to this project: " + segment.assetId()
					));
			Path path = editorPathResolver.toPath(asset.getStoragePath());
			editorPathResolver.assertProjectAssetFile(projectId, path);
			if (!Files.isRegularFile(path)) {
				throw new EditorStorageException("IMAGE asset file not found on disk: " + segment.assetId());
			}
			paths.put(segment.assetId(), path);
		}
		return paths;
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "...";
	}
}
