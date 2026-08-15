package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceEditorSegmentVisualRequest {

	/**
	 * Project IMAGE asset that fills this segment's visual slot.
	 * Duration stays equal to the replaced VIDEO range so audio is unchanged.
	 */
	@NotNull(message = "assetId is required")
	private UUID assetId;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
