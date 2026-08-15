package com.vhmedia.livedownloader.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class EditorOptionsResponse {

	List<String> fps;
	List<String> resolution;
	List<String> codec;
	List<String> quality;
	List<String> segmentTypes;
	List<Double> playbackRates;
	boolean imageSegmentsEnabled;
	boolean keepOriginalAudio;
}
