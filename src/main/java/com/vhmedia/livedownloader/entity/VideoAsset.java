package com.vhmedia.livedownloader.entity;

import com.vhmedia.livedownloader.enums.AssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "video_asset")
public class VideoAsset {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 16)
	private AssetType type;

	@Column(name = "original_filename", length = 255)
	private String originalFilename;

	@Column(name = "storage_file_name", nullable = false, length = 255)
	private String storageFileName;

	@Column(name = "storage_path", nullable = false, columnDefinition = "TEXT")
	private String storagePath;

	@Column(name = "mime_type", nullable = false, length = 128)
	private String mimeType;

	@Column(name = "duration_millis")
	private Long durationMillis;

	@Column(name = "width")
	private Integer width;

	@Column(name = "height")
	private Integer height;

	@Column(name = "video_codec", length = 64)
	private String videoCodec;

	@Column(name = "audio_codec", length = 64)
	private String audioCodec;

	@Column(name = "byte_size", nullable = false)
	private long byteSize;

	@Column(name = "primary_source", nullable = false)
	private boolean primarySource;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (type == null) {
			type = AssetType.IMAGE;
		}
		if (storageFileName == null || storageFileName.isBlank()) {
			storageFileName = "asset.bin";
		}
		createdAt = Instant.now();
	}
}
