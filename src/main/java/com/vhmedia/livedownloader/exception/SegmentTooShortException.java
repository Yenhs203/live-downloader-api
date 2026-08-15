package com.vhmedia.livedownloader.exception;

/**
 * A clip would be shorter than {@code EDITOR_MIN_SEGMENT_DURATION_MS}.
 */
public class SegmentTooShortException extends ApiException {

	public SegmentTooShortException(String message) {
		super(ErrorCode.SEGMENT_TOO_SHORT, message);
	}
}
