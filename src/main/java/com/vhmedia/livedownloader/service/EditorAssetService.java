package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.dto.response.EditorAssetResponse;
import com.vhmedia.livedownloader.editor.EditorImageFormat;
import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.enums.AssetType;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.exception.EditorAssetNotFoundException;
import com.vhmedia.livedownloader.exception.EditorProjectNotFoundException;
import com.vhmedia.livedownloader.exception.EditorStorageException;
import com.vhmedia.livedownloader.exception.ErrorCode;
import com.vhmedia.livedownloader.exception.ExportAlreadyRunningException;
import com.vhmedia.livedownloader.exception.InvalidEditorFileException;
import com.vhmedia.livedownloader.exception.InvalidEditorStateException;
import com.vhmedia.livedownloader.exception.UploadTooLargeException;
import com.vhmedia.livedownloader.repository.VideoAssetRepository;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import com.vhmedia.livedownloader.util.CappedFileCopy;
import com.vhmedia.livedownloader.util.EditorPathResolver;
import com.vhmedia.livedownloader.util.SafeOriginalFilename;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class EditorAssetService {

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
	private final EditorPathResolver editorPathResolver;

	public EditorAssetService(
			EditorProperties editorProperties,
			VideoProjectRepository projectRepository,
			VideoAssetRepository assetRepository,
			VideoSegmentRepository segmentRepository,
			VideoExportJobRepository exportJobRepository,
			EditorPathResolver editorPathResolver
	) {
		this.editorProperties = editorProperties;
		this.projectRepository = projectRepository;
		this.assetRepository = assetRepository;
		this.segmentRepository = segmentRepository;
		this.exportJobRepository = exportJobRepository;
		this.editorPathResolver = editorPathResolver;
	}

	@Transactional
	public EditorAssetResponse addImage(UUID projectId, MultipartFile file) {
		assertImageFeatureEnabled();
		if (file == null || file.isEmpty()) {
			throw new InvalidEditorFileException("Image file is required");
		}
		lockMutable(projectId);
		long count = assetRepository.countByProjectIdAndType(projectId, AssetType.IMAGE);
		if (count >= editorProperties.getMaxAssetsPerProject()) {
			throw new InvalidEditorStateException(
					"Too many IMAGE assets (max " + editorProperties.getMaxAssetsPerProject() + ")"
			);
		}
		if (!EditorImageFormat.isAllowedContentType(file.getContentType())) {
			throw new InvalidEditorFileException("Unsupported image type (allowed: JPEG, PNG, WEBP)");
		}
		if (file.getSize() > editorProperties.getMaxImageUploadBytes()) {
			throw new UploadTooLargeException(
					ErrorCode.EDITOR_UPLOAD_TOO_LARGE,
					"Image exceeds maximum size (" + editorProperties.getMaxImageUploadBytes() + " bytes)"
			);
		}
		UUID assetId = UUID.randomUUID();
		Path staging = editorPathResolver.assetFile(projectId, assetId, "bin");
		Path dest = staging;
		EditorImageFormat format;
		long storedBytes;
		try {
			try (InputStream in = file.getInputStream()) {
				storedBytes = CappedFileCopy.copy(
						in,
						staging,
						editorProperties.getMaxImageUploadBytes(),
						ErrorCode.EDITOR_UPLOAD_TOO_LARGE
				);
			}
			if (storedBytes <= 0) {
				editorPathResolver.deleteQuietly(staging);
				throw new InvalidEditorFileException("Uploaded image is empty");
			}
			format = EditorImageFormat.detect(staging);
			dest = editorPathResolver.assetFile(projectId, assetId, format.extension());
			if (!staging.equals(dest)) {
				editorPathResolver.moveReplace(staging, dest);
			}
		} catch (EditorStorageException | UploadTooLargeException | InvalidEditorFileException ex) {
			editorPathResolver.deleteQuietly(staging);
			if (!dest.equals(staging)) {
				editorPathResolver.deleteQuietly(dest);
			}
			throw ex;
		} catch (IOException ex) {
			editorPathResolver.deleteQuietly(staging);
			throw new EditorStorageException("Unable to store editor image asset", ex);
		}

		VideoAsset asset = VideoAsset.builder()
				.id(assetId)
				.projectId(projectId)
				.type(AssetType.IMAGE)
				.originalFilename(SafeOriginalFilename.displayName(file.getOriginalFilename()))
				.storageFileName(SafeOriginalFilename.fileNameOf(dest))
				.storagePath(editorPathResolver.toStoredPath(dest))
				.mimeType(format.contentType())
				.byteSize(storedBytes)
				.primarySource(false)
				.build();
		assetRepository.save(asset);
		log.info("Stored editor IMAGE asset projectId={} assetId={}", projectId, assetId);
		return toResponse(asset);
	}

	@Transactional(readOnly = true)
	public List<EditorAssetResponse> list(UUID projectId) {
		findVisible(projectId);
		return assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
				.map(EditorAssetService::toResponse)
				.toList();
	}

	@Transactional
	public void delete(UUID projectId, UUID assetId) {
		assertImageFeatureEnabled();
		lockMutable(projectId);
		VideoAsset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
				.orElseThrow(() -> new EditorAssetNotFoundException("Editor asset not found: " + assetId));
		if (asset.isPrimarySource() || asset.getType() == AssetType.VIDEO) {
			throw new InvalidEditorStateException("Cannot delete the source VIDEO asset");
		}
		if (segmentRepository.existsByProjectIdAndAssetId(projectId, assetId)) {
			throw new InvalidEditorStateException("Cannot delete asset while it is used by a segment");
		}
		Path path = editorPathResolver.toPath(asset.getStoragePath());
		editorPathResolver.assertProjectAssetFile(projectId, path);
		try {
			Files.deleteIfExists(path);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to delete editor asset file", ex);
		}
		assetRepository.delete(asset);
		log.info("Deleted editor IMAGE asset projectId={} assetId={}", projectId, assetId);
	}

	@Transactional(readOnly = true)
	public AssetFileDownload getFile(UUID projectId, UUID assetId) {
		findVisible(projectId);
		VideoAsset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
				.orElseThrow(() -> new EditorAssetNotFoundException("Editor asset not found: " + assetId));
		return toDownload(projectId, asset);
	}

	@Transactional(readOnly = true)
	public AssetFileDownload getContent(UUID assetId) {
		VideoAsset asset = assetRepository.findById(assetId)
				.orElseThrow(() -> new EditorAssetNotFoundException("Editor asset not found: " + assetId));
		findVisible(asset.getProjectId());
		return toDownload(asset.getProjectId(), asset);
	}

	@Transactional(readOnly = true)
	public AssetFileDownload getPrimarySource(UUID projectId) {
		findVisible(projectId);
		VideoAsset asset = assetRepository.findByProjectIdAndPrimarySourceTrue(projectId)
				.orElseThrow(() -> new EditorAssetNotFoundException("Editor source asset not found for project: " + projectId));
		return toDownload(projectId, asset);
	}

	private AssetFileDownload toDownload(UUID projectId, VideoAsset asset) {
		Path path = editorPathResolver.toPath(asset.getStoragePath());
		if (asset.isPrimarySource() || asset.getType() == AssetType.VIDEO) {
			editorPathResolver.assertProjectSourceFile(projectId, path);
		} else {
			editorPathResolver.assertProjectAssetFile(projectId, path);
		}
		if (!Files.isRegularFile(path)) {
			throw new EditorStorageException("Editor asset file not found on disk");
		}
		long size;
		try {
			size = Files.size(path);
		} catch (IOException ex) {
			throw new EditorStorageException("Unable to read editor asset file", ex);
		}
		String filename = asset.getOriginalFilename() == null
				? defaultFilename(asset)
				: SafeOriginalFilename.displayName(asset.getOriginalFilename());
		if (filename == null) {
			filename = defaultFilename(asset);
		}
		String contentType = asset.getMimeType() == null || asset.getMimeType().isBlank()
				? (asset.getType() == AssetType.VIDEO ? "video/mp4" : "application/octet-stream")
				: asset.getMimeType();
		return new AssetFileDownload(
				new FileSystemResource(path),
				filename,
				size,
				contentType
		);
	}

	private static String defaultFilename(VideoAsset asset) {
		if (asset.getType() == AssetType.VIDEO) {
			return asset.getStorageFileName() == null ? "source.mp4" : asset.getStorageFileName();
		}
		return asset.getStorageFileName() == null
				? asset.getId() + "." + EditorImageFormat.fromStoredMime(asset.getMimeType()).extension()
				: asset.getStorageFileName();
	}

	public static EditorAssetResponse toResponse(VideoAsset asset) {
		return EditorAssetResponse.builder()
				.id(asset.getId())
				.type(asset.getType())
				.originalFilename(asset.getOriginalFilename())
				.storageFileName(asset.getStorageFileName())
				.mimeType(asset.getMimeType())
				.contentType(asset.getMimeType())
				.byteSize(asset.getByteSize())
				.durationMillis(asset.getDurationMillis())
				.width(asset.getWidth())
				.height(asset.getHeight())
				.videoCodec(asset.getVideoCodec())
				.audioCodec(asset.getAudioCodec())
				.primarySource(asset.isPrimarySource())
				.createdAt(asset.getCreatedAt())
				.build();
	}

	private void assertImageFeatureEnabled() {
		if (!editorProperties.isImageSegmentsEnabled()) {
			throw new InvalidEditorStateException(
					"IMAGE assets are not enabled in Phase 1A (set EDITOR_IMAGE_SEGMENTS_ENABLED=true for Phase 1B)"
			);
		}
	}

	private VideoProject findVisible(UUID projectId) {
		VideoProject project = projectRepository.findById(projectId)
				.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
		if (project.getStatus() == ProjectStatus.DELETED) {
			throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
		}
		return project;
	}

	private VideoProject lockMutable(UUID projectId) {
		VideoProject project = projectRepository.findByIdForUpdate(projectId)
				.orElseThrow(() -> new EditorProjectNotFoundException("Editor project not found: " + projectId));
		if (project.getStatus() == ProjectStatus.DELETED) {
			throw new EditorProjectNotFoundException("Editor project not found: " + projectId);
		}
		if (project.getStatus() != ProjectStatus.CREATED && project.getStatus() != ProjectStatus.READY) {
			throw new InvalidEditorStateException("Cannot update assets while project status is " + project.getStatus());
		}
		if (exportJobRepository.existsByProjectIdAndStatusIn(projectId, ACTIVE_EXPORT)) {
			throw new ExportAlreadyRunningException("Cannot update assets while an export job is running");
		}
		return project;
	}

	public record AssetFileDownload(Resource resource, String filename, long contentLength, String contentType) {
	}
}
