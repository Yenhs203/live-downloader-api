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
public class ResizeEditorBoundaryRequest {

	/**
	 * New shared cut on the source timeline (integer ms). Becomes left {@code sourceEndMillis}
	 * and right {@code sourceStartMillis}. No gap and no overlap.
	 */
	@Schema(
			description = "New shared cut (integer ms). Left sourceEnd and right sourceStart both become this value.",
			example = "6000",
			requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotNull(message = "boundaryMillis is required")
	@PositiveOrZero
	private Long boundaryMillis;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
