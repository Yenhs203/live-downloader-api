package com.vhmedia.livedownloader.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class HealthResponse {

	String status;
	String application;
	Instant timestamp;
}
