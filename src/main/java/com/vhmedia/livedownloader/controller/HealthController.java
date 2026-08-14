package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.dto.response.HealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

	@GetMapping
	public ResponseEntity<HealthResponse> health() {
		HealthResponse response = HealthResponse.builder()
				.status("UP")
				.application("VH MEDIA LIVE DOWNLOADER")
				.timestamp(Instant.now())
				.build();
		return ResponseEntity.ok(response);
	}
}
