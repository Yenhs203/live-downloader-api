package com.vhmedia.livedownloader.exception;

/**
 * Editor output duration (sum of visual clip durations after speed) is not greater than 0.
 */
public class InvalidOutputDurationException extends ApiException {

	public InvalidOutputDurationException(String message) {
		super(ErrorCode.INVALID_OUTPUT_DURATION, message);
	}
}
