package com.vhmedia.livedownloader.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReorderEditorTimelineRequest {

	/**
	 * Visual order. Must list every project segment exactly once. Client positions are ignored.
	 */
	@Schema(description = "Visual order. Must list every project segment exactly once. Audio is not reordered.")
	@NotEmpty(message = "segmentIds must not be empty")
	@Size(max = 50, message = "too many segments")
	private List<@NotNull UUID> segmentIds;

	@Schema(description = "If set, must match project.timelineVersion. Stale values return TIMELINE_CONFLICT (409). Omit to skip the check.")
	@PositiveOrZero
	private Long timelineVersion;
}
