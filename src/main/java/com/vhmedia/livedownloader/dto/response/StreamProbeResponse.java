package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamProbeResponse {

	boolean valid;
	boolean hasVideo;
	boolean hasAudio;
	String format;
	VideoInfo video;
	AudioInfo audio;

	@Value
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class VideoInfo {
		String codec;
		Integer width;
		Integer height;
		Double fps;
	}

	@Value
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AudioInfo {
		String codec;
		Integer sampleRate;
		Integer channels;
	}
}
