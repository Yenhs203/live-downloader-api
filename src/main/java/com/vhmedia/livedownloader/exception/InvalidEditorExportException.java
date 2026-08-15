package com.vhmedia.livedownloader.exception;

public class InvalidEditorExportException extends ApiException {

	public InvalidEditorExportException(String message) {
		super(ErrorCode.INVALID_EDITOR_EXPORT, message);
	}
}
