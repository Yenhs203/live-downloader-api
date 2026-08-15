package com.vhmedia.livedownloader.dto.request;

import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Array order is the output <em>visual</em> order ({@code position}).
 * Each item is either a VIDEO source slice or an IMAGE asset loop.
 * Audio is not part of this payload. Export audio stays {@code original[0..outputDuration]}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEditorSegmentsRequest {

	@NotEmpty(message = "segments must not be empty")
	@Size(max = 50, message = "too many segments")
	@Valid
	private List<EditorSegmentInput> segments;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;

	@Getter
	@Setter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class EditorSegmentInput {

		@Size(max = 32, message = "label must be at most 32 characters")
		private String label;

		/**
		 * {@code VIDEO} (default) or {@code IMAGE}.
		 */
		private String type;

		/**
		 * Inclusive start on the source timeline (VIDEO).
		 */
		@PositiveOrZero
		private Long sourceStartMillis;

		/**
		 * Exclusive end on the source timeline (VIDEO).
		 */
		@PositiveOrZero
		private Long sourceEndMillis;

		/**
		 * Legacy alias for {@link #sourceStartMillis}.
		 */
		@PositiveOrZero
		private Long startMillis;

		/**
		 * Legacy alias for {@link #sourceEndMillis}.
		 */
		@PositiveOrZero
		private Long endMillis;

		/**
		 * Required for {@code IMAGE}. Visual hold duration in integer ms.
		 * Must equal the replaced VIDEO slot so audio duration is unchanged.
		 */
		@PositiveOrZero
		private Long durationMillis;

		/**
		 * Required for {@code IMAGE}.
		 */
		private UUID assetId;

		/**
		 * Optional VIDEO playback rate. Default 1.0.
		 */
		private Double playbackRate;

		public EditorSegmentType resolvedType() {
			return EditorSegmentType.fromApi(type);
		}

		public long resolvedSourceStartMillis() {
			Long value = sourceStartMillis != null ? sourceStartMillis : startMillis;
			if (value == null) {
				throw new InvalidEditorSegmentsException("sourceStartMillis is required for VIDEO segments");
			}
			return value;
		}

		public long resolvedSourceEndMillis() {
			Long value = sourceEndMillis != null ? sourceEndMillis : endMillis;
			if (value == null) {
				throw new InvalidEditorSegmentsException("sourceEndMillis is required for VIDEO segments");
			}
			return value;
		}
	}
}
