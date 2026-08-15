package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Crop the current visual output to {@code [startMillis, endMillis)}.
 * Canonical project-level trim: 27.167s → 25.000s is {@code startMillis=0, endMillis=25000}.
 * Rewrites segment ranges; there is no separate stored output window.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetEditorOutputRangeRequest {

	@Schema(description = "Inclusive start on the current visual output timeline (integer ms).", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "startMillis is required")
	@PositiveOrZero
	private Long startMillis;

	@Schema(description = "Exclusive end on the current visual output timeline (integer ms).", example = "25000", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "endMillis is required")
	@PositiveOrZero
	private Long endMillis;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
