package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorSourceResponse {

	boolean hasVideo;
	boolean hasAudio;
	String videoCodec;
	String audioCodec;
	Integer width;
	Integer height;
	Double fps;
	Long durationMillis;
}
