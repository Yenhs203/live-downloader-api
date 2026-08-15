package com.vhmedia.livedownloader.exception;

public class InvalidEditorSegmentsException extends ApiException {

	public InvalidEditorSegmentsException(String message) {
		super(ErrorCode.INVALID_TIMELINE, message);
	}

	protected InvalidEditorSegmentsException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
