package com.vhmedia.livedownloader.exception;

public class ExportNotReadyException extends InvalidEditorStateException {

	public ExportNotReadyException(String message) {
		super(ErrorCode.EXPORT_NOT_READY, message);
	}
}
