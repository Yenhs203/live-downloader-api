package com.vhmedia.livedownloader.exception;

/**
 * Shared-cut {@code boundaryMillis} is outside the pair, not on a VIDEO neighbor pair, or otherwise invalid.
 * Pieces shorter than the minimum still use {@link SegmentTooShortException}.
 */
public class InvalidSegmentBoundaryException extends InvalidEditorSegmentsException {

	public InvalidSegmentBoundaryException(String message) {
		super(ErrorCode.INVALID_SEGMENT_BOUNDARY, message);
	}
}
