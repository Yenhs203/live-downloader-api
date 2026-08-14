package com.vhmedia.livedownloader.media;

/**
 * Why an FFmpeg recording process ended.
 */
public enum RecordingExitReason {
	/**
	 * Stream ended / FFmpeg exited with code 0 without a user stop request.
	 * Downstream should trigger remux.
	 */
	COMPLETED_NATURALLY,

	/**
	 * User requested graceful stop.
	 */
	STOPPED_BY_USER,

	/**
	 * FFmpeg exited with a non-zero code or failed to start.
	 */
	FAILED
}
