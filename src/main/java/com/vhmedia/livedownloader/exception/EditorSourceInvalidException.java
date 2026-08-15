package com.vhmedia.livedownloader.exception;

public class EditorSourceInvalidException extends ApiException {

	public EditorSourceInvalidException(String message) {
		super(ErrorCode.EDITOR_PROBE_FAILED, message);
	}

	public EditorSourceInvalidException(String message, Throwable cause) {
		super(ErrorCode.EDITOR_PROBE_FAILED, message, cause);
	}
}
