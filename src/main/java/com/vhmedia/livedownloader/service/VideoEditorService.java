package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.dto.request.UpdateEditorExportRequest;
import com.vhmedia.livedownloader.dto.response.EditorAssetResponse;
import com.vhmedia.livedownloader.dto.response.EditorExportResponse;
import com.vhmedia.livedownloader.dto.response.EditorOptionsResponse;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.dto.response.EditorSegmentResponse;
import com.vhmedia.livedownloader.dto.response.EditorSourceResponse;
import com.vhmedia.livedownloader.editor.EditorMp4Format;
import com.vhmedia.livedownloader.editor.EditorExportCodec;
import com.vhmedia.livedownloader.editor.EditorExportFps;
import com.vhmedia.livedownloader.editor.EditorExportPlan;
import com.vhmedia.livedownloader.editor.EditorExportPlanner;
import com.vhmedia.livedownloader.editor.EditorExportQuality;
import com.vhmedia.livedownloader.editor.EditorExportResolution;
import com.vhmedia.livedownloader.editor.EditorExportSettings;
import com.vhmedia.livedownloader.editor.EditorPlaybackRate;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.EditorSegmentValidator;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.editor.SegmentMapper;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.AssetType;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.enums.EditorSourceStorageMode;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import com.vhmedia.livedownloader.exception.EditorAssetNotFoundException;
import com.vhmedia.livedownloader.exception.EditorExportNotFoundException;
import com.vhmedia.livedownloader.exception.EditorProjectNotFoundException;
import com.vhmedia.livedownloader.exception.EditorSourceInvalidException;
import com.vhmedia.livedownloader.exception.EditorStorageException;
import com.vhmedia.livedownloader.exception.ErrorCode;
import com.vhmedia.livedownloader.exception.ExportAlreadyRunningException;
import com.vhmedia.livedownloader.exception.ExportNotReadyException;
import com.vhmedia.livedownloader.exception.InvalidEditorExportException;
import com.vhmedia.livedownloader.exception.InvalidEditorFileException;
import com.vhmedia.livedownloader.exception.InvalidEditorStateException;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.TimelineConflictException;
import com.vhmedia.livedownloader.exception.UploadTooLargeException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.repository.VideoAssetRepository;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import com.vhmedia.livedownloader.util.CappedFileCopy;
import com.vhmedia.livedownloader.util.EditorPathResolver;
import com.vhmedia.livedownloader.util.OutputBaseNameGenerator;
import com.vhmedia.livedownloader.util.RecordingImportMode;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import com.vhmedia.livedownloader.util.SafeOriginalFilename;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VideoEditorService {

	private static final List<ExportStatus> ACTIVE_EXPORT = List.of(
			ExportStatus.CREATED,
			ExportStatus.PREPARING,
			ExportStatus.RENDERING,
			ExportStatus.FINALIZING
	);

	private final EditorProperties editorProperties;
	private final VideoProjectRepository projectRepository;
	private final VideoAssetRepository assetRepository;
	private final VideoSegmentRepository segmentRepository;
	private final VideoExportJobRepository exportJobRepository;
	private final LiveDownloadJobRepository recordingRepository;
	private final RecordingPathResolver recordingPathResolver;
	private final EditorPathResolver editorPathResolver;
	private final FfprobeService ffprobeService;
	private final EditorSegmentValidator segmentValidator;
	private final EditorExportPlanner exportPlanner;
	private final EditorAssetService editorAssetService;
	private final TransactionTemplate transactionTemplate;

	public VideoEditorService(
			EditorProperties editorProperties,
			VideoProjectRepository projectRepository,
			VideoAssetRepository assetRepository,
			VideoSegmentRepository segmentRepository,
			VideoExportJobRepository exportJobRepository,
			LiveDownloadJobRepository recordingRepository,
			RecordingPathResolver recordingPathResolver,
			EditorPathResolver editorPathResolver,
			FfprobeService ffprobeService,
			EditorSegmentValidator segmentValidator,
			EditorExportPlanner exportPlanner,
			EditorAssetService editorAssetService
	) {
		this(
				editorProperties,
				projectRepository,
				assetRepository,
				segmentRepository,
				exportJobRepository,
				recordingRepository,
				recordingPathResolver,
				editorPathResolver,
				ffprobeService,
				segmentValidator,
				exportPlanner,
				editorAssetService,
				null
		);
	}

	@Autowired
	public VideoEditorService(
			EditorProperties editorProperties,
			VideoProjectRepository projectRepository,
			VideoAssetRepository assetRepository,
			VideoSegmentRepository segmentRepository,
			VideoExportJobRepository exportJobRepository,
			LiveDownloadJobRepository recordingRepository,
			RecordingPathResolver recordingPathResolver,
			EditorPathResolver editorPathResolver,
			FfprobeService ffprobeService,
			EditorSegmentValidator segmentValidator,
			EditorExportPlanner exportPlanner,
			EditorAssetService editorAssetService,
			PlatformTransactionManager transactionManager
	) {
		this.editorProperties = editorProperties;
		this.projectRepository = projectRepository;
		this.assetRepository = assetRepository;
		this.segmentRepository = segmentRepository;
		this.exportJobRepository = exportJobRepository;
		this.recordingRepository = recordingRepository;
		this.recordingPathResolver = recordingPathResolver;
		this.editorPathResolver = editorPathResolver;
		this.ffprobeService = ffprobeService;
		this.segmentValidator = segmentValidator;
		this.exportPlanner = exportPlanner;
		this.editorAssetService = editorAssetService;
		this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
	}

	public EditorProjectResponse createFromRecording(UUID recordingId, String name) {
		recordingPathResolver.assertWritableAndHasFreeSpace();
		editorPathResolver.assertWritableAndHasFreeSpace();
		LiveDownloadJob recording = recordingRepository.findById(recordingId)
				.filter(job -> job.getStatus() != LiveJobStatus.DELETED)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + recordingId));
		if (recording.getStatus() != LiveJobStatus.COMPLETED) {
			throw new InvalidRecordingStateException(
					"Editor source recording must be COMPLETED (current: " + recording.getStatus() + ")"
			);
		}
		if (recording.getFinalFilePath() == null || recording.getFinalFilePath().isBlank()) {
			throw new EditorStorageException("Completed recording file is not available");
		}
		Path recordingMp4 = recordingPathResolver.toPath(recording.getFinalFilePath());
		if (!Files.isRegularFile(recordingMp4)) {
			throw new EditorStorageException("Completed recording file not found on disk");
		}

		UUID projectId = UUID.randomUUID();
		Path source = editorPathResolver.newSourceFile(projectId);
		RecordingImportMode mode;
		try {
			editorPathResolver.createProjectDirectory(projectId);
			mode = editorPathResolver.importRecordingSource(projectId, recordingMp4, source);
			log.info("Imported recording into editor projectId={} recordingId={} mode={}", projectId, recordingId, mode);
		} catch (RuntimeException ex) {
			cleanupQuietly(projectId);
			throw ex;
		}

		EditorSourceStorageMode storageMode = mode == RecordingImportMode.COPY
				? EditorSourceStorageMode.RECORDING_COPY
				: EditorSourceStorageMode.RECORDING_HARDLINK;
		return persistProbedProject(
				projectId,
				VideoEditSourceType.RECORDING,
				recording.getId(),
				trimToNull(name),
				source,
				recording.getOutputBaseName() == null ? "source.mp4" : recording.getOutputBaseName() + ".mp4",
				storageMode
		);
	}

	public EditorProjectResponse createFromUpload(MultipartFile file, String name) {
		recordingPathResolver.assertWritableAndHasFreeSpace();
		editorPathResolver.assertWritableAndHasFreeSpace();
		validateUpload(file);

		UUID projectId = UUID.randomUUID();
		Path source = editorPathResolver.newSourceFile(projectId);
		try {
			editorPathResolver.createProjectDirectory(projectId);
			try (InputStream in = file.getInputStream()) {
				CappedFileCopy.copy(in, source, editorProperties.getMaxUploadBytes(), ErrorCode.EDITOR_UPLOAD_TOO_LARGE);
			}
			long size = Files.size(source);
			if (size <= 0) {
				cleanupQuietly(projectId);
				throw new InvalidEditorFileException("Uploaded file is empty");
			}
			EditorMp4Format.assertMp4(source);
		} catch (UploadTooLargeException | InvalidEditorFileException | EditorStorageException ex) {
			cleanupQuietly(projectId);
			throw ex;
		} catch (IOException ex) {
			cleanupQuietly(projectId);
			throw new EditorStorageException("Unable to store uploaded editor source", ex);
		}

		return persistProbedProject(
				projectId,
				VideoEditSourceType.UPLOAD,
				null,
				trimToNull(name),
				source,
				SafeOriginalFilename.displayName(file.getOriginalFilename()),
				EditorSourceStorageMode.UPLOAD
		);
	}

	@Transactional
	public EditorProjectResponse updateExportSettings(UUID projectId, UpdateEditorExportRequest request) {
		VideoProject project = lockMutableProject(projectId);
		EditorExportSettings merged = mergeExport(settingsOf(project), request);
		applyExportSettings(project, merged);
		projectRepository.save(project);
		log.info(
				"Updated editor export projectId={} fps={} resolution={} codec={} quality={}",
				projectId,
				merged.fps().apiValue(),
				merged.resolution().apiValue(),
				merged.codec().apiValue(),
				merged.quality().apiValue()
		);
		return toResponse(project);
	}

	public EditorOptionsResponse options() {
		return EditorOptionsResponse.builder()
				.fps(java.util.Arrays.stream(EditorExportFps.values()).map(EditorExportFps::apiValue).toList())
				.resolution(java.util.Arrays.stream(EditorExportResolution.values()).map(EditorExportResolution::apiValue).toList())
				.codec(java.util.Arrays.stream(EditorExportCodec.values()).map(EditorExportCodec::apiValue).toList())
				.quality(java.util.Arrays.stream(EditorExportQuality.values()).map(EditorExportQuality::apiValue).toList())
				.segmentTypes(java.util.Arrays.stream(EditorSegmentType.values()).map(Enum::name).toList())
				.playbackRates(EditorPlaybackRate.ALLOWED)
				.imageSegmentsEnabled(editorProperties.isImageSegmentsEnabled())
				.keepOriginalAudio(true)
				.build();
	}

	@Transactional(readOnly = true)
	public EditorProjectResponse get(UUID projectId) {
		return toResponse(findVisible(projectId));
	}

	@Transactional(readOnly = true)
	public Page<EditorProjectResponse> list(ProjectStatus status, Pageable pageable) {
		Page<VideoProject> page = status == null
				? projectRepository.findByStatusNot(ProjectStatus.DELETED, pageable)
				: projectRepository.findByStatus(status, pageable);
		return page.map(this::toResponse);
	}

	@Transactional
	public void delete(UUID projectId) {
		VideoProject project = lockVisible(projectId);
		if (exportJobRepository.existsByProjectIdAndStatusIn(projectId, ACTIVE_EXPORT)) {
			throw new ExportAlreadyRunningException("Cannot delete project while an export job is running; cancel it first");
		}
		if (project.getStatus() != ProjectStatus.CREATED && project.getStatus() != ProjectStatus.READY) {
			throw new InvalidEditorStateException("Cannot delete project in status " + project.getStatus());
		}
		project.setStatus(ProjectStatus.DELETED);
		projectRepository.save(project);
		runAfterCommit(() -> {
			try {
				editorPathResolver.deleteProjectDirectory(projectId);
			} catch (RuntimeException ex) {
				log.warn("Failed to cleanup editor files after delete projectId={}: {}", projectId, ex.getMessage());
			}
		});
		log.info(
				"Deleted editor projectId={} sourceStorageMode={}",
				projectId,
				project.getSourceStorageMode()
		);
	}

	@Transactional(readOnly = true)
	public EditorFileDownload getDownload(UUID projectId) {
		findVisible(projectId);
		VideoExportJob job = exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
				.filter(item -> item.getStatus() == ExportStatus.COMPLETED)
				.orElseThrow(() -> new ExportNotReadyException("File download is only available after a COMPLETED export"));
		if (job.getOutputFilePath() == null || job.getOutputFilePath().isBlank()) {
			throw new EditorStorageException("Editor output file is not available");
		}
		Path actual = editorPathResolver.toPath(job.getOutputFilePath());
		editorPathResolver.assertProjectExportFile(projectId, actual);
		if (!Files.isRegularFile(actual)) {
			throw new EditorStorageException("Editor output file not found on disk");
		}
		long contentLength;
		try {
			contentLength = Files.size(actual);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to read editor output file", ex);
		}
		VideoProject project = findVisible(projectId);
		return new EditorFileDownload(new FileSystemResource(actual), project.getOutputBaseName() + ".mp4", contentLength);
	}

	@Transactional(readOnly = true)
	public EditorFileDownload getExportDownload(UUID exportId) {
		VideoExportJob job = exportJobRepository.findById(exportId)
				.orElseThrow(() -> new EditorExportNotFoundException("Editor export not found: " + exportId));
		VideoProject project = findVisible(job.getProjectId());
		if (job.getStatus() != ExportStatus.COMPLETED) {
			throw new ExportNotReadyException("File download is only available for COMPLETED exports");
		}
		if (job.getOutputFilePath() == null || job.getOutputFilePath().isBlank()) {
			throw new EditorStorageException("Editor output file is not available");
		}
		Path actual = editorPathResolver.toPath(job.getOutputFilePath());
		editorPathResolver.assertProjectExportFile(project.getId(), actual);
		if (!Files.isRegularFile(actual)) {
			throw new EditorStorageException("Editor output file not found on disk");
		}
		long contentLength;
		try {
			contentLength = Files.size(actual);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to read editor output file", ex);
		}
		return new EditorFileDownload(new FileSystemResource(actual), project.getOutputBaseName() + ".mp4", contentLength);
	}

	private EditorProjectResponse persistProbedProject(
			UUID projectId,
			VideoEditSourceType sourceType,
			UUID sourceRecordingId,
			String name,
			Path source,
			String originalFilename,
			EditorSourceStorageMode storageMode
	) {
		StreamProbeResult probe;
		try {
			probe = ffprobeService.probeLocalFile(source);
		} catch (StreamProbeException ex) {
			cleanupQuietly(projectId);
			throw new EditorSourceInvalidException("Unable to read editor source video");
		}
		if (!probe.isHasVideo()) {
			cleanupQuietly(projectId);
			throw new EditorSourceInvalidException("Editor source must contain a video stream");
		}
		if (probe.getDurationMillis() == null || probe.getDurationMillis() <= 0) {
			cleanupQuietly(projectId);
			throw new EditorSourceInvalidException("Editor source duration could not be determined");
		}

		try {
			return inTransaction(status -> insertCreatedProject(
					projectId,
					sourceType,
					sourceRecordingId,
					name,
					source,
					originalFilename,
					storageMode,
					probe
			));
		} catch (RuntimeException ex) {
			cleanupQuietly(projectId);
			try {
				projectRepository.findById(projectId).ifPresent(this::cleanupFailedCreate);
			} catch (RuntimeException ignored) {
				// files already removed
			}
			throw ex;
		}
	}

	private EditorProjectResponse insertCreatedProject(
			UUID projectId,
			VideoEditSourceType sourceType,
			UUID sourceRecordingId,
			String name,
			Path source,
			String originalFilename,
			EditorSourceStorageMode storageMode,
			StreamProbeResult probe
	) {
		VideoProject project = VideoProject.builder()
				.id(projectId)
				.name(name)
				.sourceType(sourceType)
				.sourceRecordingId(sourceRecordingId)
				.sourceStorageMode(storageMode)
				.outputBaseName(OutputBaseNameGenerator.generateEditor())
				.status(ProjectStatus.CREATED)
				.hasVideo(false)
				.hasAudio(false)
				.exportFps("ORIGINAL")
				.exportResolution("ORIGINAL")
				.exportCodec("H264")
				.exportQuality("BALANCED")
				.exportKeepOriginalAudio(true)
				.build();
		project = projectRepository.save(project);

		long byteSize = 0L;
		try {
			byteSize = Files.size(source);
		} catch (IOException ignored) {
			// optional
		}
		UUID assetId = UUID.randomUUID();
		VideoAsset sourceAsset = VideoAsset.builder()
				.id(assetId)
				.projectId(projectId)
				.type(AssetType.VIDEO)
				.originalFilename(originalFilename == null ? "source.mp4" : originalFilename)
				.storageFileName(SafeOriginalFilename.fileNameOf(source))
				.storagePath(editorPathResolver.toStoredPath(source))
				.mimeType("video/mp4")
				.durationMillis(probe.getDurationMillis())
				.width(probe.getWidth())
				.height(probe.getHeight())
				.videoCodec(probe.getVideoCodec())
				.audioCodec(probe.getAudioCodec())
				.byteSize(byteSize)
				.primarySource(true)
				.build();
		assetRepository.save(sourceAsset);

		List<EditorSegment> defaultSegments = segmentValidator.defaultFullSpan(probe.getDurationMillis());
		replaceSegments(projectId, assetId, defaultSegments.stream()
				.map(segment -> EditorSegment.video(segment.id(), segment.label(), 0L, probe.getDurationMillis(), assetId))
				.toList());

		project.setSourceAssetId(assetId);
		project.setHasVideo(true);
		project.setHasAudio(probe.isHasAudio());
		project.setVideoCodec(probe.getVideoCodec());
		project.setAudioCodec(probe.getAudioCodec());
		project.setWidth(probe.getWidth());
		project.setHeight(probe.getHeight());
		project.setFps(probe.getFps());
		project.setDurationMillis(probe.getDurationMillis());
		project.setStatus(ProjectStatus.READY);
		project = projectRepository.save(project);
		log.info(
				"Created editor project id={} source={} storageMode={} durationMs={} width={} height={} fps={} videoCodec={} audioCodec={} hasAudio={}",
				projectId,
				sourceType,
				storageMode,
				probe.getDurationMillis(),
				probe.getWidth(),
				probe.getHeight(),
				probe.getFps(),
				probe.getVideoCodec(),
				probe.getAudioCodec(),
				probe.isHasAudio()
		);
		return toResponse(project);
	}

	private <T> T inTransaction(TransactionCallback<T> action) {
		if (transactionTemplate != null) {
			return transactionTemplate.execute(action);
		}
		try {
			return action.doInTransaction(null);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void runAfterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
			return;
		}
		action.run();
	}

	void replaceSegments(UUID projectId, UUID sourceAssetId, List<EditorSegment> visual) {
		segmentRepository.deleteByProjectId(projectId);
		segmentRepository.flush();
		List<VideoSegment> rows = new ArrayList<>(visual.size());
		for (int i = 0; i < visual.size(); i++) {
			rows.add(SegmentMapper.toEntity(projectId, sourceAssetId, i, visual.get(i)));
		}
		segmentRepository.saveAll(rows);
	}

	private void validateUpload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidEditorFileException("MP4 file is required");
		}
		if (file.getSize() > editorProperties.getMaxUploadBytes()) {
			throw new UploadTooLargeException(
					ErrorCode.EDITOR_UPLOAD_TOO_LARGE,
					"Uploaded file exceeds the maximum allowed size (" + editorProperties.getMaxUploadBytes() + " bytes)"
			);
		}
		if (!EditorMp4Format.isAllowedContentType(file.getContentType())) {
			throw new InvalidEditorFileException("Only MP4 files are accepted");
		}
		String original = file.getOriginalFilename();
		if (!SafeOriginalFilename.looksLikeMp4(original)) {
			throw new InvalidEditorFileException("Only MP4 files are accepted");
		}
	}

	VideoProject findVisible(UUID projectId) {
		VideoProject project = projectRepository.findById(projectId)
				.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
		if (project.getStatus() == ProjectStatus.DELETED) {
			throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
		}
		return project;
	}

	VideoProject lockVisible(UUID projectId) {
		VideoProject project = projectRepository.findByIdForUpdate(projectId)
				.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
		if (project.getStatus() == ProjectStatus.DELETED) {
			throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
		}
		return project;
	}

	VideoProject lockMutableProject(UUID projectId) {
		VideoProject project = lockVisible(projectId);
		if (project.getStatus() != ProjectStatus.CREATED && project.getStatus() != ProjectStatus.READY) {
			throw new InvalidEditorStateException("Cannot update project in status " + project.getStatus());
		}
		if (exportJobRepository.existsByProjectIdAndStatusIn(projectId, ACTIVE_EXPORT)) {
			throw new ExportAlreadyRunningException("Cannot update project while an export job is running");
		}
		return project;
	}

	/**
	 * Optional stale-client check. Omitted {@code expected} skips the check (compat).
	 * Always used together with {@link #lockMutableProject} so split and resize cannot race.
	 */
	void requireMatchingTimelineVersion(VideoProject project, Long expected) {
		if (expected == null) {
			return;
		}
		if (project.getTimelineVersion() != expected) {
			throw new TimelineConflictException(
					"Timeline was updated by another request. Reload the project and retry."
			);
		}
	}

	/**
	 * Force a dirty JPA update so {@code @Version} increments even when status was already READY.
	 */
	void markTimelineDirty(VideoProject project) {
		project.setStatus(ProjectStatus.READY);
		project.setUpdatedAt(Instant.now());
	}

	private void cleanupFailedCreate(VideoProject project) {
		try {
			editorPathResolver.deleteProjectDirectory(project.getId());
		} catch (RuntimeException ex) {
			log.warn("Failed to cleanup editor files projectId={}: {}", project.getId(), ex.getMessage());
		}
		try {
			project.setSourceAssetId(null);
			project.setStatus(ProjectStatus.DELETED);
			projectRepository.save(project);
		} catch (RuntimeException ex) {
			log.warn("Failed to mark failed editor create as deleted projectId={}: {}", project.getId(), ex.getMessage());
		}
	}

	private void cleanupQuietly(UUID projectId) {
		try {
			editorPathResolver.deleteProjectDirectory(projectId);
		} catch (RuntimeException ex) {
			log.warn("Failed to cleanup editor files projectId={}: {}", projectId, ex.getMessage());
		}
	}

	EditorProjectResponse toResponse(VideoProject project) {
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(project.getId());
		List<EditorSegment> segments = SegmentMapper.toDomain(rows);
		List<EditorSegmentResponse> segmentResponses = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			VideoSegment row = rows.get(i);
			EditorSegment prev = i > 0 ? segments.get(i - 1) : null;
			EditorSegment left = segments.get(i);
			EditorSegment right = i + 1 < segments.size() ? segments.get(i + 1) : null;
			boolean shareRight = segmentValidator.canShareSourceBoundary(left, right, project.getSourceAssetId());
			long visualDurationMillis = left.durationMillis();
			segmentResponses.add(EditorSegmentResponse.builder()
					.id(row.getId().toString())
					.label(row.getLabel())
					.type(row.getType())
					.sourceStartMillis(row.getSourceStartMillis())
					.sourceEndMillis(row.getSourceEndMillis())
					.sourceDurationMillis(left.hasSourceSlot() ? left.sourceDurationMillis() : null)
					.durationMillis(visualDurationMillis)
					.playbackRate(row.getPlaybackRate() <= 0.0d ? 1.0d : row.getPlaybackRate())
					.visualDurationMillis(visualDurationMillis)
					.assetId(row.getAssetId())
					.position(row.getPosition())
					.canMergeNext(shareRight)
					.canResizeRightBoundary(shareRight)
					.canResizeLeftBoundary(segmentValidator.canShareSourceBoundary(prev, left, project.getSourceAssetId()))
					.build());
		}
		EditorExportSettings exportSettings = settingsOf(project);
		EditorExportPlan plan = exportPlanner.plan(project.getWidth(), project.getHeight(), project.getFps(), exportSettings);
		VideoExportJob latest = exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null);
		EditorSourceResponse source = EditorSourceResponse.builder()
				.hasVideo(project.isHasVideo())
				.hasAudio(project.isHasAudio())
				.videoCodec(project.getVideoCodec())
				.audioCodec(project.getAudioCodec())
				.width(project.getWidth())
				.height(project.getHeight())
				.fps(project.getFps())
				.durationMillis(project.getDurationMillis())
				.build();
		EditorExportResponse export = toExportResponse(latest, plan);
		long sourceDurationMillis = EditorTimelineDurations.sourceDurationMillis(project.getDurationMillis());
		long outputDurationMillis = EditorTimelineDurations.outputDurationMillis(segments);
		List<EditorAssetResponse> assets = editorAssetService.list(project.getId());
		return EditorProjectResponse.builder()
				.id(project.getId())
				.status(project.getStatus())
				.sourceType(project.getSourceType())
				.sourceRecordingId(project.getSourceRecordingId())
				.sourceAssetId(project.getSourceAssetId())
				.name(project.getName())
				.title(project.getName())
				.hasVideo(project.isHasVideo())
				.hasAudio(project.isHasAudio())
				.videoCodec(project.getVideoCodec())
				.audioCodec(project.getAudioCodec())
				.width(project.getWidth())
				.height(project.getHeight())
				.fps(project.getFps())
				.durationMillis(sourceDurationMillis)
				.sourceDurationMillis(sourceDurationMillis)
				.outputDurationMillis(outputDurationMillis)
				.outputBytes(latest != null ? latest.getOutputBytes() : null)
				.outputBaseName(project.getOutputBaseName())
				.source(source)
				.export(export)
				.visualDurationMillis(outputDurationMillis)
				.timelineVersion(project.getTimelineVersion())
				.segments(segmentResponses)
				.assets(assets)
				.errorMessage(latest != null ? clientExportError(latest) : null)
				.createdAt(project.getCreatedAt())
				.renderedAt(latest != null ? latest.getCompletedAt() : null)
				.updatedAt(project.getUpdatedAt())
				.build();
	}

	static EditorExportResponse toExportResponse(VideoExportJob job, EditorExportPlan plan) {
		EditorExportResponse.EditorExportResponseBuilder builder = EditorExportResponse.builder()
				.fps(plan.settings().fps().apiValue())
				.requestedFps(plan.settings().fps().isOriginal() ? null : plan.settings().fps().fps() == null
						? null
						: plan.settings().fps().fps().intValue())
				.resolution(plan.settings().resolution().apiValue())
				.codec(plan.settings().codec().apiValue())
				.quality(plan.settings().quality().apiValue())
				.keepOriginalAudio(plan.settings().keepOriginalAudio())
				.outputWidth(plan.outputWidth())
				.outputHeight(plan.outputHeight())
				.outputFps(plan.outputFps())
				.outputVideoCodec(plan.settings().codec().outputCodecName());
		if (job == null) {
			return builder.build();
		}
		return builder
				.id(job.getId())
				.status(job.getStatus())
				.fps(job.getFpsPreset())
				.requestedFps(job.getRequestedFps())
				.resolution(job.getResolution())
				.codec(job.getVideoCodec())
				.quality(job.getQuality())
				.keepOriginalAudio(job.isKeepOriginalAudio())
				.progressMillis(job.getProgressMillis())
				.progressPercent(job.getProgressPercent())
				.outputBytes(job.getOutputBytes())
				.errorMessage(clientExportError(job))
				.cancelRequested(job.isCancelRequested())
				.createdAt(job.getCreatedAt())
				.startedAt(job.getStartedAt())
				.completedAt(job.getCompletedAt())
				.build();
	}

	public EditorExportSettings mergeExport(EditorExportSettings current, UpdateEditorExportRequest request) {
		EditorExportSettings base = current == null ? EditorExportSettings.defaults() : current.normalized();
		if (request == null) {
			return base;
		}
		EditorExportFps fps = request.getFps() != null ? EditorExportFps.fromApi(request.getFps()) : base.fps();
		EditorExportResolution resolution = request.getResolution() != null
				? EditorExportResolution.fromApi(request.getResolution())
				: base.resolution();
		EditorExportCodec codec = request.resolvedCodec() != null
				? EditorExportCodec.fromApi(request.resolvedCodec())
				: base.codec();
		EditorExportQuality quality = request.getQuality() != null
				? EditorExportQuality.fromApi(request.getQuality())
				: base.quality();
		if (Boolean.FALSE.equals(request.getKeepOriginalAudio())) {
			throw new InvalidEditorExportException("V1 always keeps the original audio timeline (keepOriginalAudio must be true)");
		}
		return new EditorExportSettings(fps, resolution, codec, quality, true).normalized();
	}

	public static EditorExportSettings settingsOf(VideoProject project) {
		return new EditorExportSettings(
				EditorExportFps.fromApi(project.getExportFps()),
				EditorExportResolution.fromApi(project.getExportResolution()),
				EditorExportCodec.fromApi(project.getExportCodec()),
				EditorExportQuality.fromApi(project.getExportQuality()),
				project.isExportKeepOriginalAudio()
		).normalized();
	}

	static String clientExportError(VideoExportJob job) {
		if (job == null || job.getErrorMessage() == null || job.getErrorMessage().isBlank()) {
			return null;
		}
		if (job.getStatus() == ExportStatus.CANCELLED) {
			return "Export cancelled";
		}
		return ErrorCode.EXPORT_FAILED.getDefaultMessage();
	}

	static void applyExportSettings(VideoProject project, EditorExportSettings settings) {
		EditorExportSettings merged = settings.normalized();
		project.setExportFps(merged.fps().apiValue());
		project.setExportResolution(merged.resolution().apiValue());
		project.setExportCodec(merged.codec().apiValue());
		project.setExportQuality(merged.quality().apiValue());
		project.setExportKeepOriginalAudio(merged.keepOriginalAudio());
	}

	Set<UUID> assetIds(UUID projectId) {
		return assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
				.map(VideoAsset::getId)
				.collect(Collectors.toUnmodifiableSet());
	}

	static UUID requireSourceAssetId(VideoProject project) {
		if (project.getSourceAssetId() == null) {
			throw new EditorAssetNotFoundException("Project source asset is missing");
		}
		return project.getSourceAssetId();
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public record EditorFileDownload(Resource resource, String filename, long contentLength) {
	}
}
