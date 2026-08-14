package com.vhmedia.livedownloader.exception;

public class InvalidRecordingStateException extends ApiException {

	public InvalidRecordingStateException(String message) {
		super(ErrorCode.INVALID_RECORDING_STATE, message);
	}
}
