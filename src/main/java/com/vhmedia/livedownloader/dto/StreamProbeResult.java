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
}
