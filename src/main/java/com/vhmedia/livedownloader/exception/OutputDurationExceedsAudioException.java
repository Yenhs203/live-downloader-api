package com.vhmedia.livedownloader.exception;

/**
 * Slow-motion (or other visual lengthening) would make output longer than the locked original audio.
 * V1 does not loop audio or insert silence.
 */
public class OutputDurationExceedsAudioException extends ApiException {

	public OutputDurationExceedsAudioException(String message) {
		super(ErrorCode.OUTPUT_DURATION_EXCEEDS_AUDIO, message);
	}
}
