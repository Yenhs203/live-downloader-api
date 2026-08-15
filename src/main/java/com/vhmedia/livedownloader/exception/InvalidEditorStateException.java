package com.vhmedia.livedownloader.exception;

public class InvalidEditorStateException extends ApiException {

	public InvalidEditorStateException(String message) {
		super(ErrorCode.INVALID_EDITOR_STATE, message);
	}

	protected InvalidEditorStateException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
