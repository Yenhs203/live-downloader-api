package com.vhmedia.livedownloader.exception;

/**
 * Two timeline neighbors cannot be merged or share a movable cut.
 */
public class SegmentsNotMergeableException extends ApiException {

	public SegmentsNotMergeableException(String message) {
		super(ErrorCode.SEGMENTS_NOT_MERGEABLE, message);
	}
}
