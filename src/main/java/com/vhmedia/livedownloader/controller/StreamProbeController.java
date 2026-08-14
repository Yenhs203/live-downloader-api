package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.dto.request.ProbeStreamRequest;
import com.vhmedia.livedownloader.dto.response.StreamProbeResponse;
import com.vhmedia.livedownloader.service.StreamProbeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/streams")
public class StreamProbeController {

	private final StreamProbeService streamProbeService;

	public StreamProbeController(StreamProbeService streamProbeService) {
		this.streamProbeService = streamProbeService;
	}

	@PostMapping("/probe")
	public ResponseEntity<StreamProbeResponse> probe(@Valid @RequestBody ProbeStreamRequest request) {
		StreamProbeResponse response = streamProbeService.probe(request.getUrl());
		return ResponseEntity.ok(response);
	}
}
