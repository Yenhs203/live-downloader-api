package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.dto.response.RecordingJobResponse;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.GlobalExceptionHandler;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.media.RecordingEventHub;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.service.RecordingJobService;
import com.vhmedia.livedownloader.service.RecordingLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecordingJobController.class)
@Import(GlobalExceptionHandler.class)
class RecordingJobControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RecordingJobService recordingJobService;

	@MockitoBean
	private RecordingLifecycleService recordingLifecycleService;

	@MockitoBean
	private RecordingEventHub recordingEventHub;

	@MockitoBean
	private LiveDownloadJobRepository jobRepository;

	@Test
	void createReturnsAcceptedWithRecordingPayload() throws Exception {
		UUID id = UUID.randomUUID();
		when(recordingJobService.createAndStart(anyString())).thenReturn(sample(id, LiveJobStatus.RECORDING));

		mockMvc.perform(post("/api/v1/recordings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "streamUrl": "https://cdn.example.com/live.flv?token=abc" }
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("RECORDING"))
				.andExpect(jsonPath("$.hasVideo").value(true))
				.andExpect(jsonPath("$.videoCodec").value("h264"));
	}

	@Test
	void listReturnsPage() throws Exception {
		UUID id = UUID.randomUUID();
		Page<RecordingJobResponse> page = new PageImpl<>(List.of(sample(id, LiveJobStatus.COMPLETED)));
		when(recordingJobService.list(isNull(), eq(false), any(Pageable.class))).thenReturn(page);

		mockMvc.perform(get("/api/v1/recordings").param("page", "0").param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(id.toString()))
				.andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
	}

	@Test
	void getReturnsDetail() throws Exception {
		UUID id = UUID.randomUUID();
		when(recordingJobService.get(id)).thenReturn(sample(id, LiveJobStatus.COMPLETED));

		mockMvc.perform(get("/api/v1/recordings/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
	}

	@Test
	void stopReturnsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		doNothing().when(recordingLifecycleService).requestStop(id);

		mockMvc.perform(post("/api/v1/recordings/{id}/stop", id))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("STOPPING"));
	}

	@Test
	void stopMissingJobReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new RecordingNotFoundException("Recording job not found: " + id))
				.when(recordingLifecycleService).requestStop(id);

		mockMvc.perform(post("/api/v1/recordings/{id}/stop", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
	}

	@Test
	void stopInvalidStateReturns409() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new InvalidRecordingStateException("Cannot stop job in status FAILED"))
				.when(recordingLifecycleService).requestStop(id);

		mockMvc.perform(post("/api/v1/recordings/{id}/stop", id))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_RECORDING_STATE"));
	}

	@Test
	void createBlankUrlReturns400() throws Exception {
		mockMvc.perform(post("/api/v1/recordings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "streamUrl": "   " }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void downloadRejectsNonCompletedReturns409() throws Exception {
		UUID id = UUID.randomUUID();
		when(recordingJobService.getDownload(id))
				.thenThrow(new InvalidRecordingStateException("File download is only available for COMPLETED jobs"));

		mockMvc.perform(get("/api/v1/recordings/{id}/file", id))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_RECORDING_STATE"));
	}

	@Test
	void downloadMissingFileReturns500StorageError() throws Exception {
		UUID id = UUID.randomUUID();
		when(recordingJobService.getDownload(id))
				.thenThrow(new com.vhmedia.livedownloader.exception.StorageException("Final recording file not found on disk"));

		mockMvc.perform(get("/api/v1/recordings/{id}/file", id))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
	}

	@Test
	void deleteReturnsNoContent() throws Exception {
		UUID id = UUID.randomUUID();
		doNothing().when(recordingJobService).delete(id);

		mockMvc.perform(delete("/api/v1/recordings/{id}", id))
				.andExpect(status().isNoContent());
	}

	@Test
	void deleteActiveJobReturnsConflict() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new InvalidRecordingStateException("Cannot delete job while status is RECORDING"))
				.when(recordingJobService).delete(id);

		mockMvc.perform(delete("/api/v1/recordings/{id}", id))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_RECORDING_STATE"))
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void downloadReturnsAttachmentStream() throws Exception {
		UUID id = UUID.randomUUID();
		byte[] bytes = new byte[]{1, 2, 3, 4};
		when(recordingJobService.getDownload(id)).thenReturn(
				new RecordingJobService.RecordingFileDownload(
						new ByteArrayResource(bytes),
						"live_demo.mp4",
						bytes.length
				)
		);

		mockMvc.perform(get("/api/v1/recordings/{id}/file", id))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"live_demo.mp4\""))
				.andExpect(header().string("Content-Type", "video/mp4"));
	}

	@Test
	void getMissingJobReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		when(recordingJobService.get(id)).thenThrow(new RecordingNotFoundException("Recording job not found: " + id));

		mockMvc.perform(get("/api/v1/recordings/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void eventsRequiresExistingJob() throws Exception {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/recordings/{id}/events", id).accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(status().isNotFound());
	}

	private static RecordingJobResponse sample(UUID id, LiveJobStatus status) {
		return RecordingJobResponse.builder()
				.id(id)
				.status(status)
				.hasVideo(true)
				.hasAudio(true)
				.videoCodec("h264")
				.audioCodec("aac")
				.width(1080)
				.height(1920)
				.createdAt(Instant.parse("2026-08-11T07:00:00Z"))
				.startedAt(Instant.parse("2026-08-11T07:00:01Z"))
				.build();
	}
}
