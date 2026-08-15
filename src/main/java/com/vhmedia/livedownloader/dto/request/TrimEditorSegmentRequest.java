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
public class TrimEditorSegmentRequest {

	@Schema(description = "Inclusive start on the source timeline (integer ms).", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "sourceStartMillis is required")
	@PositiveOrZero
	private Long sourceStartMillis;

	@Schema(description = "Exclusive end on the source timeline (integer ms).", example = "25000", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "sourceEndMillis is required")
	@PositiveOrZero
	private Long sourceEndMillis;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
