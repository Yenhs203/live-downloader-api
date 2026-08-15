package com.vhmedia.livedownloader.exception;

public class InvalidEditorFileException extends ApiException {

	public InvalidEditorFileException(String message) {
		super(ErrorCode.INVALID_EDITOR_FILE, message);
	}
}
