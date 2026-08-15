package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.ExportStatus;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorEventResponse {

	UUID exportId;
	UUID projectId;
	ExportStatus status;
	Long processedMillis;
	Long durationMillis;
	Double progressPercent;
	Double fps;
	Double speed;
}
