package com.vhmedia.livedownloader.exception;

/**
 * {@code PUT .../speed} is VIDEO-only. IMAGE clips keep a fixed visual duration slot.
 */
public class PlaybackRateNotSupportedForImageException extends ApiException {

	public PlaybackRateNotSupportedForImageException() {
		super(
				ErrorCode.PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE,
				"IMAGE clips have a fixed duration slot and do not support playbackRate."
		);
	}
}
