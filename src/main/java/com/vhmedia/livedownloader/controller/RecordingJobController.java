package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.config.OpenApiConfig;
import com.vhmedia.livedownloader.dto.request.CreateRecordingRequest;
import com.vhmedia.livedownloader.dto.response.RecordingJobResponse;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.media.RecordingEventHub;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.service.RecordingJobService;
import com.vhmedia.livedownloader.service.RecordingLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recordings")
@Tag(name = OpenApiConfig.TAG_RECORDINGS)
public class RecordingJobController {

	private final RecordingJobService recordingJobService;
	private final RecordingLifecycleService recordingLifecycleService;
	private final RecordingEventHub recordingEventHub;
	private final LiveDownloadJobRepository jobRepository;

	public RecordingJobController(
			RecordingJobService recordingJobService,
			RecordingLifecycleService recordingLifecycleService,
			RecordingEventHub recordingEventHub,
			LiveDownloadJobRepository jobRepository
	) {
		this.recordingJobService = recordingJobService;
		this.recordingLifecycleService = recordingLifecycleService;
		this.recordingEventHub = recordingEventHub;
		this.jobRepository = jobRepository;
	}

	@PostMapping
	@Operation(summary = "Start recording", description = "Probes then records a direct HTTP(S) stream URL. Query tokens are redacted in logs.")
	public ResponseEntity<RecordingJobResponse> create(@Valid @RequestBody CreateRecordingRequest request) {
		RecordingJobResponse response = recordingJobService.createAndStart(request.getStreamUrl());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	@GetMapping
	public ResponseEntity<Page<RecordingJobResponse>> list(
			@RequestParam(required = false) LiveJobStatus status,
			@RequestParam(required = false, defaultValue = "false") boolean activeOnly,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return ResponseEntity.ok(recordingJobService.list(status, activeOnly, pageable));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get recording job")
	public ResponseEntity<RecordingJobResponse> get(@PathVariable("id") UUID id) {
		return ResponseEntity.ok(recordingJobService.get(id));
	}

	@PostMapping("/{id}/stop")
	public ResponseEntity<Map<String, Object>> stop(@PathVariable("id") UUID id) {
		recordingLifecycleService.requestStop(id);
		return ResponseEntity.accepted().body(Map.of(
				"id", id.toString(),
				"status", "STOPPING"
		));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
		recordingJobService.delete(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/file")
	@Operation(summary = "Download completed MP4")
	public ResponseEntity<Resource> download(@PathVariable("id") UUID id) {
		RecordingJobService.RecordingFileDownload download = recordingJobService.getDownload(id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
				.contentType(MediaType.parseMediaType("video/mp4"))
				.contentLength(download.contentLength())
				.body(download.resource());
	}

	@GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(summary = "Recording SSE events")
	public SseEmitter events(@PathVariable("id") UUID id) {
		jobRepository.findById(id)
				.filter(job -> job.getStatus() != LiveJobStatus.DELETED)
				.orElseThrow(() -> new RecordingNotFoundException("Recording job not found: " + id));
		return recordingEventHub.subscribe(id);
	}
}
