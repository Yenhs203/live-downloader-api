package com.vhmedia.livedownloader.exception;

public class ExportAlreadyRunningException extends InvalidEditorStateException {

	public ExportAlreadyRunningException(String message) {
		super(ErrorCode.EXPORT_ALREADY_RUNNING, message);
	}
}
