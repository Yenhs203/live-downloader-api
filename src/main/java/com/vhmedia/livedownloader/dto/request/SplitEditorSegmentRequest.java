package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
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
public class SplitEditorSegmentRequest {

	/**
	 * Exclusive end of the left piece / inclusive start of the right piece, on the source timeline.
	 */
	@Schema(description = "Exclusive end of the left piece / inclusive start of the right piece, on the source timeline (integer ms).", example = "12000", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "atMillis is required")
	@PositiveOrZero
	private Long atMillis;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
