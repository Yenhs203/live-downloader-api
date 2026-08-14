package com.vhmedia.livedownloader.exception;

public class NoVideoStreamException extends ApiException {

	public NoVideoStreamException(String message) {
		super(ErrorCode.STREAM_PROBE_FAILED, message);
	}
}
