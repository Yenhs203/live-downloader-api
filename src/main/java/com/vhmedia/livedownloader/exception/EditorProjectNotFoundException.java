package com.vhmedia.livedownloader.exception;

public class EditorProjectNotFoundException extends ApiException {

	public EditorProjectNotFoundException(String message) {
		super(ErrorCode.EDITOR_PROJECT_NOT_FOUND, message);
	}
}
