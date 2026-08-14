package com.vhmedia.livedownloader.exception;

public class StreamProbeException extends ApiException {

	public StreamProbeException(String message) {
		super(ErrorCode.STREAM_PROBE_FAILED, message);
	}

	public StreamProbeException(String message, Throwable cause) {
		super(ErrorCode.STREAM_PROBE_FAILED, message, cause);
	}

	protected StreamProbeException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	protected StreamProbeException(ErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}
}
