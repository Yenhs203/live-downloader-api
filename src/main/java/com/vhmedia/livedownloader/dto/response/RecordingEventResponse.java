package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingEventResponse {

	UUID jobId;
	LiveJobStatus status;
	Long durationMillis;
	Long downloadedBytes;
	Double fps;
	String speed;
	String bitrate;
}
