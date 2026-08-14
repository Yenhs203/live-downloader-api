package com.vhmedia.livedownloader.controller;

import com.vhmedia.livedownloader.dto.response.StreamProbeResponse;
import com.vhmedia.livedownloader.exception.GlobalExceptionHandler;
import com.vhmedia.livedownloader.exception.InvalidStreamUrlException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.service.StreamProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StreamProbeController.class)
@Import(GlobalExceptionHandler.class)
class StreamProbeControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StreamProbeService streamProbeService;

	@Test
	void probeReturnsMappedResponse() throws Exception {
		StreamProbeResponse response = StreamProbeResponse.builder()
				.valid(true)
				.hasVideo(true)
				.hasAudio(true)
				.format("flv")
				.video(StreamProbeResponse.VideoInfo.builder()
						.codec("h264")
						.width(1080)
						.height(1920)
						.fps(30.0d)
						.build())
				.audio(StreamProbeResponse.AudioInfo.builder()
						.codec("aac")
						.sampleRate(44100)
						.channels(2)
						.build())
				.build();

		when(streamProbeService.probe(anyString())).thenReturn(response);

		mockMvc.perform(post("/api/v1/streams/probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "url": "https://cdn.example.com/live.flv?token=abc" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.hasVideo").value(true))
				.andExpect(jsonPath("$.hasAudio").value(true))
				.andExpect(jsonPath("$.format").value("flv"))
				.andExpect(jsonPath("$.video.codec").value("h264"))
				.andExpect(jsonPath("$.video.width").value(1080))
				.andExpect(jsonPath("$.video.height").value(1920))
				.andExpect(jsonPath("$.video.fps").value(30.0))
				.andExpect(jsonPath("$.audio.codec").value("aac"))
				.andExpect(jsonPath("$.audio.sampleRate").value(44100))
				.andExpect(jsonPath("$.audio.channels").value(2));
	}

	@Test
	void probeRejectsBlankUrlWith400() throws Exception {
		mockMvc.perform(post("/api/v1/streams/probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "url": "   " }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors.url").exists());
	}

	@Test
	void probeMapsInvalidUrlTo400() throws Exception {
		when(streamProbeService.probe(anyString()))
				.thenThrow(new InvalidStreamUrlException("Only http and https stream URLs are allowed"));

		mockMvc.perform(post("/api/v1/streams/probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "url": "file:///tmp/a.ts" }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STREAM_URL"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.path").value("/api/v1/streams/probe"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	void probeMapsProbeFailureTo422() throws Exception {
		when(streamProbeService.probe(anyString()))
				.thenThrow(new StreamProbeException("ffprobe stderr leak"));

		mockMvc.perform(post("/api/v1/streams/probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "url": "https://cdn.example.com/live.flv" }
								"""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("STREAM_PROBE_FAILED"))
				.andExpect(jsonPath("$.status").value(422))
				.andExpect(jsonPath("$.message").value("Unable to read stream."))
				.andExpect(jsonPath("$.path").value("/api/v1/streams/probe"));
	}

	@Test
	void probeMapsTimeoutTo504() throws Exception {
		when(streamProbeService.probe(anyString()))
				.thenThrow(new StreamProbeTimeoutException("Stream probe timed out."));

		mockMvc.perform(post("/api/v1/streams/probe")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "url": "https://cdn.example.com/live.flv" }
								"""))
				.andExpect(status().isGatewayTimeout())
				.andExpect(jsonPath("$.code").value("STREAM_PROBE_TIMEOUT"))
				.andExpect(jsonPath("$.message").value("Stream probe timed out."));
	}
}
