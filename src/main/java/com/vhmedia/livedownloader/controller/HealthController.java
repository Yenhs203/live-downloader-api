package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.config.OpenApiConfig;
import com.vhmedia.livedownloader.dto.response.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = OpenApiConfig.TAG_HEALTH)
public class HealthController {

	@GetMapping
	@Operation(summary = "Liveness", description = "Lightweight process liveness for UI and load balancers.")
	public ResponseEntity<HealthResponse> health() {
		HealthResponse response = HealthResponse.builder()
				.status("UP")
				.application("VH MEDIA LIVE DOWNLOADER")
				.timestamp(Instant.now())
				.build();
		return ResponseEntity.ok(response);
	}
}
