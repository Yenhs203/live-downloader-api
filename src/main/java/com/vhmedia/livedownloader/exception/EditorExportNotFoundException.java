package com.vhmedia.livedownloader.exception;

public class EditorExportNotFoundException extends ApiException {

	public EditorExportNotFoundException(String message) {
		super(ErrorCode.EXPORT_NOT_FOUND, message);
	}
}
