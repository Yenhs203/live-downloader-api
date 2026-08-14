package com.vhmedia.livedownloader.exception;

public class StreamProbeTimeoutException extends StreamProbeException {

	public StreamProbeTimeoutException(String message) {
		super(ErrorCode.STREAM_PROBE_TIMEOUT, message);
	}
}
