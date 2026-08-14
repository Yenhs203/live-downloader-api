package com.vhmedia.livedownloader.exception;

public class RecordingNotFoundException extends ApiException {

	public RecordingNotFoundException(String message) {
		super(ErrorCode.RECORDING_NOT_FOUND, message);
	}
}
