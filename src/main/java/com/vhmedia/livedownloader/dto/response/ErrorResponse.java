package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * RFC-style API error body. Never includes stack traces.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ErrorResponse {

	Instant timestamp;
	int status;
	String code;
	String message;
	String path;
	Map<String, String> fieldErrors;
}
