package com.vhmedia.livedownloader.entity;

import com.vhmedia.livedownloader.enums.LiveJobStatus;
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
@Table(name = "live_download_job")
public class LiveDownloadJob {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
	private String originalUrl;

	@Column(name = "output_base_name", nullable = false, length = 255, unique = true)
	private String outputBaseName;

	@Column(name = "temp_file_path", columnDefinition = "TEXT")
	private String tempFilePath;

	@Column(name = "final_file_path", columnDefinition = "TEXT")
	private String finalFilePath;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private LiveJobStatus status;

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

	@Column(name = "downloaded_bytes")
	private Long downloadedBytes;

	@Column(name = "duration_millis")
	private Long durationMillis;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "stopped_at")
	private Instant stoppedAt;

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
			status = LiveJobStatus.CREATED;
		}
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
