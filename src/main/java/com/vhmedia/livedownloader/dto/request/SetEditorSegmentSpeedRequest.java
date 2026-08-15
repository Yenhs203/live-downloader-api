package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetEditorSegmentSpeedRequest {

	@Schema(
			description = "Visual playback rate. V1 whitelist: 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0. Audio is not re-timed.",
			example = "2.0",
			requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotNull(message = "playbackRate is required")
	@DecimalMin(value = "0.25", message = "playbackRate must be at least 0.25")
	@DecimalMax(value = "4.0", message = "playbackRate must be at most 4.0")
	private Double playbackRate;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
