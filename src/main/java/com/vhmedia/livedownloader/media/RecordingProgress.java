package com.vhmedia.livedownloader.media;

import lombok.Builder;
import lombok.Value;

/**
 * Snapshot of FFmpeg {@code -progress pipe:1} key/value properties.
 */
@Value
@Builder
public class RecordingProgress {

	Long outTimeMs;
	Long totalSize;
	String speed;
	Double fps;
	String bitrate;
	String progress;

	public boolean isEnd() {
		return "end".equalsIgnoreCase(progress);
	}
}
