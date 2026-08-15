package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.config.OpenApiConfig;
import com.vhmedia.livedownloader.dto.request.ProbeStreamRequest;
import com.vhmedia.livedownloader.dto.response.StreamProbeResponse;
import com.vhmedia.livedownloader.service.StreamProbeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/streams")
@Tag(name = OpenApiConfig.TAG_STREAM_PROBE)
public class StreamProbeController {

	private final StreamProbeService streamProbeService;

	public StreamProbeController(StreamProbeService streamProbeService) {
		this.streamProbeService = streamProbeService;
	}

	@PostMapping("/probe")
	@Operation(summary = "Probe a stream URL", description = "Runs ffprobe on a direct HTTP(S) URL. Query tokens are never returned.")
	public ResponseEntity<StreamProbeResponse> probe(@Valid @RequestBody ProbeStreamRequest request) {
		StreamProbeResponse response = streamProbeService.probe(request.getUrl());
		return ResponseEntity.ok(response);
	}
}
