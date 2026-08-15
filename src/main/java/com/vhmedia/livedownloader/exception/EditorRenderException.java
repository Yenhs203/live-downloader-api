package com.vhmedia.livedownloader.exception;

public class EditorRenderException extends ApiException {

	public EditorRenderException(String message) {
		super(ErrorCode.EXPORT_FAILED, message);
	}

	public EditorRenderException(String message, Throwable cause) {
		super(ErrorCode.EXPORT_FAILED, message, cause);
	}
}
