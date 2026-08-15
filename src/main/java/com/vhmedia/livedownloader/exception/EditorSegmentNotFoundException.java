package com.vhmedia.livedownloader.exception;

public class EditorSegmentNotFoundException extends ApiException {

	public EditorSegmentNotFoundException(String message) {
		super(ErrorCode.EDITOR_SEGMENT_NOT_FOUND, message);
	}
}
