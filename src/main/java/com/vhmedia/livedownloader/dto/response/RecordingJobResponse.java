package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingJobResponse {

	UUID id;
	LiveJobStatus status;
	boolean hasVideo;
	boolean hasAudio;
	String videoCodec;
	String audioCodec;
	Integer width;
	Integer height;
	Double fps;
	Long downloadedBytes;
	Long durationMillis;
	String errorMessage;
	String outputBaseName;
	Instant createdAt;
	Instant startedAt;
	Instant stoppedAt;
	Instant completedAt;
	Instant updatedAt;
}
