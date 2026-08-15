package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.ExportStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorExportResponse {

	UUID id;
	ExportStatus status;
	String fps;
	Integer requestedFps;
	String resolution;
	String codec;
	String quality;
	boolean keepOriginalAudio;
	Integer outputWidth;
	Integer outputHeight;
	Double outputFps;
	String outputVideoCodec;
	Long progressMillis;
	Double progressPercent;
	Long outputBytes;
	String errorMessage;
	boolean cancelRequested;
	Instant createdAt;
	Instant startedAt;
	Instant completedAt;
}
