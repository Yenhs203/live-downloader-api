package com.vhmedia.livedownloader.exception;

/**
 * Trim would expand a clip, move a shared cut, or target a middle clip's inner edge.
 * Pieces shorter than the minimum still use {@link SegmentTooShortException}.
 */
public class InvalidSegmentTrimException extends InvalidEditorSegmentsException {

	public InvalidSegmentTrimException(String message) {
		super(ErrorCode.INVALID_SEGMENT_TRIM, message);
	}
}
