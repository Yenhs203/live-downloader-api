package com.vhmedia.livedownloader.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StreamProbeResult {

	boolean hasVideo;
	boolean hasAudio;

	String formatName;

	String videoCodec;
	Integer width;
	Integer height;
	Double fps;

	String audioCodec;
	Integer audioSampleRate;
	Integer audioChannels;

	/**
	 * Container / format duration from ffprobe {@code format.duration}.
	 */
	Long durationMillis;
	/**
	 * First video stream duration (stream {@code duration} or {@code tags.DURATION}).
	 */
	Long videoDurationMillis;
	/**
	 * First audio stream duration (stream {@code duration} or {@code tags.DURATION}).
	 */
	Long audioDurationMillis;
}
