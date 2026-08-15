package com.vhmedia.livedownloader.entity;

import com.vhmedia.livedownloader.enums.ExportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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
@Table(name = "video_export_job")
public class VideoExportJob {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ExportStatus status;

	@Column(name = "fps_preset", nullable = false, length = 32)
	private String fpsPreset;

	@Column(name = "requested_fps")
	private Integer requestedFps;

	@Column(name = "resolution", nullable = false, length = 32)
	private String resolution;

	@Column(name = "video_codec", nullable = false, length = 32)
	private String videoCodec;

	@Column(name = "quality", nullable = false, length = 32)
	@Builder.Default
	private String quality = "BALANCED";

	@Column(name = "keep_original_audio", nullable = false)
	@Builder.Default
	private boolean keepOriginalAudio = true;

	@Column(name = "progress_millis")
	private Long progressMillis;

	@Column(name = "progress_percent")
	private Double progressPercent;

	@Column(name = "output_file_path", columnDefinition = "TEXT")
	private String outputFilePath;

	@Column(name = "output_bytes")
	private Long outputBytes;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "cancel_requested", nullable = false)
	private boolean cancelRequested;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (status == null) {
			status = ExportStatus.CREATED;
		}
		if (fpsPreset == null || fpsPreset.isBlank()) {
			fpsPreset = "ORIGINAL";
		}
		if (resolution == null || resolution.isBlank()) {
			resolution = "ORIGINAL";
		}
		if (videoCodec == null || videoCodec.isBlank()) {
			videoCodec = "H264";
		}
		if (quality == null || quality.isBlank()) {
			quality = "BALANCED";
		}
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
