package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorSegmentResponse {

	String id;
	String label;
	EditorSegmentType type;
	/**
	 * Inclusive start on the source timeline (VIDEO only, integer ms).
	 */
	Long sourceStartMillis;
	/**
	 * Exclusive end on the source timeline (VIDEO only, integer ms).
	 */
	Long sourceEndMillis;
	/**
	 * Source range length ({@code sourceEnd - sourceStart}) when a source slot is stored.
	 */
	@Schema(description = "Source range length (sourceEndMillis - sourceStartMillis). Null for IMAGE without a stored slot.")
	Long sourceDurationMillis;
	/**
	 * Visual duration of this clip (after speed for VIDEO). Alias of {@link #visualDurationMillis}.
	 */
	@Schema(description = "Visual duration after speed (compat alias of visualDurationMillis).")
	long durationMillis;
	/**
	 * Visual playback rate. Always {@code 1.0} for IMAGE.
	 */
	@Builder.Default
	double playbackRate = 1.0d;
	/**
	 * Visual duration after speed (VIDEO) or hold duration (IMAGE).
	 */
	@Schema(description = "Visual duration after playbackRate. IMAGE hold duration is unchanged by speed.")
	long visualDurationMillis;
	/**
	 * Uploaded still used when {@code type=IMAGE}.
	 */
	UUID assetId;
	int position;
	/**
	 * True when this clip and the next visual neighbor share a source cut (undo-split / merge).
	 */
	@Schema(description = "True when the next visual neighbor is the same VIDEO source cut (undo-split).")
	boolean canMergeNext;
	/**
	 * True when the right edge is a draggable shared source cut with the next visual neighbor.
	 * False after reorder when neighbors are not source-contiguous (e.g. C 20..30 | A 0..10).
	 */
	@Schema(description = "True when PUT .../boundary against the next visual neighbor is allowed.")
	boolean canResizeRightBoundary;
	/**
	 * True when the left edge is a draggable shared source cut with the previous visual neighbor.
	 */
	@Schema(description = "True when the previous visual neighbor can PUT .../boundary against this clip.")
	boolean canResizeLeftBoundary;
}
