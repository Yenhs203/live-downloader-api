package com.vhmedia.livedownloader.entity;

import com.vhmedia.livedownloader.enums.EditorSegmentType;
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
@Table(name = "video_segment")
public class VideoSegment {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "asset_id", nullable = false)
	private UUID assetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 16)
	private EditorSegmentType type;

	@Column(name = "label", length = 32)
	private String label;

	@Column(name = "source_start_millis")
	private Long sourceStartMillis;

	@Column(name = "source_end_millis")
	private Long sourceEndMillis;

	@Column(name = "duration_millis", nullable = false)
	private long durationMillis;

	/**
	 * Visual playback rate. 1.0 = original speed. Audio is never re-timed.
	 */
	@Column(name = "playback_rate", nullable = false)
	@Builder.Default
	private double playbackRate = 1.0d;

	@Column(name = "position", nullable = false)
	private int position;

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
		if (type == null) {
			type = EditorSegmentType.VIDEO;
		}
		if (playbackRate <= 0.0d) {
			playbackRate = 1.0d;
		}
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
