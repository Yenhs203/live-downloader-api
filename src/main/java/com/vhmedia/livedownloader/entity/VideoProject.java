package com.vhmedia.livedownloader.entity;

import com.vhmedia.livedownloader.enums.EditorSourceStorageMode;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_project")
public class VideoProject {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "name", length = 255)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ProjectStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 32)
	private VideoEditSourceType sourceType;

	@Column(name = "source_recording_id")
	private UUID sourceRecordingId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_storage_mode", nullable = false, length = 32)
	@Builder.Default
	private EditorSourceStorageMode sourceStorageMode = EditorSourceStorageMode.UPLOAD;

	@Column(name = "source_asset_id")
	private UUID sourceAssetId;

	@Column(name = "output_base_name", nullable = false, length = 255, unique = true)
	private String outputBaseName;

	@Column(name = "has_video", nullable = false)
	private boolean hasVideo;

	@Column(name = "has_audio", nullable = false)
	private boolean hasAudio;

	@Column(name = "video_codec", length = 64)
	private String videoCodec;

	@Column(name = "audio_codec", length = 64)
	private String audioCodec;

	@Column(name = "width")
	private Integer width;

	@Column(name = "height")
	private Integer height;

	@Column(name = "fps")
	private Double fps;

	@Column(name = "duration_millis")
	private Long durationMillis;

	@Column(name = "export_fps", nullable = false, length = 32)
	private String exportFps;

	@Column(name = "export_resolution", nullable = false, length = 32)
	private String exportResolution;

	@Column(name = "export_codec", nullable = false, length = 32)
	private String exportCodec;

	@Column(name = "export_quality", nullable = false, length = 32)
	@Builder.Default
	private String exportQuality = "BALANCED";

	@Column(name = "export_keep_original_audio", nullable = false)
	@Builder.Default
	private boolean exportKeepOriginalAudio = true;

	/**
	 * JPA optimistic lock / client {@code timelineVersion}. Incremented on every project write.
	 * Timeline mutations also take {@code PESSIMISTIC_WRITE} so split and resize cannot corrupt positions.
	 */
	@Version
	@Column(name = "timeline_version", nullable = false)
	@Builder.Default
	private long timelineVersion = 0L;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (status == null) {
			status = ProjectStatus.CREATED;
		}
		if (exportFps == null || exportFps.isBlank()) {
			exportFps = "ORIGINAL";
		}
		if (exportResolution == null || exportResolution.isBlank()) {
			exportResolution = "ORIGINAL";
		}
		if (exportCodec == null || exportCodec.isBlank()) {
			exportCodec = "H264";
		}
		if (exportQuality == null || exportQuality.isBlank()) {
			exportQuality = "BALANCED";
		}
		if (sourceStorageMode == null) {
			sourceStorageMode = sourceType == VideoEditSourceType.RECORDING
					? EditorSourceStorageMode.RECORDING_HARDLINK
					: EditorSourceStorageMode.UPLOAD;
		}
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
