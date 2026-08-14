package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.dto.response.StreamProbeResponse;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.util.StreamUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamProbeServiceTest {

	@Mock
	private StreamUrlValidator streamUrlValidator;

	@Mock
	private FfprobeService ffprobeService;

	private StreamProbeService streamProbeService;

	@BeforeEach
	void setUp() {
		streamProbeService = new StreamProbeService(streamUrlValidator, ffprobeService);
	}

	@Test
	void probesAndMapsResult() {
		doNothing().when(streamUrlValidator).validate(anyString());
		when(ffprobeService.probe("https://cdn.example.com/live.flv")).thenReturn(
				StreamProbeResult.builder()
						.hasVideo(true)
						.hasAudio(true)
						.formatName("flv")
						.videoCodec("h264")
						.width(1080)
						.height(1920)
						.fps(30.0d)
						.audioCodec("aac")
						.audioSampleRate(44100)
						.audioChannels(2)
						.build()
		);

		StreamProbeResponse response = streamProbeService.probe("https://cdn.example.com/live.flv");

		assertThat(response.isValid()).isTrue();
		assertThat(response.getFormat()).isEqualTo("flv");
		assertThat(response.getVideo().getCodec()).isEqualTo("h264");
		assertThat(response.getAudio().getSampleRate()).isEqualTo(44100);
		verify(streamUrlValidator).validate("https://cdn.example.com/live.flv");
	}

	@Test
	void sanitizesProbeFailureMessage() {
		doNothing().when(streamUrlValidator).validate(anyString());
		when(ffprobeService.probe(anyString()))
				.thenThrow(new StreamProbeException("ffprobe failed with exit code 1: Internal stderr leak"));

		assertThatThrownBy(() -> streamProbeService.probe("https://cdn.example.com/live.flv"))
				.isInstanceOf(StreamProbeException.class)
				.hasMessage("Unable to read stream.")
				.hasMessageNotContaining("stderr");
	}

	@Test
	void sanitizesTimeoutMessage() {
		doNothing().when(streamUrlValidator).validate(anyString());
		when(ffprobeService.probe(anyString()))
				.thenThrow(new StreamProbeTimeoutException("ffprobe timed out after 30 seconds"));

		assertThatThrownBy(() -> streamProbeService.probe("https://cdn.example.com/live.flv"))
				.isInstanceOf(StreamProbeTimeoutException.class)
				.hasMessage("Stream probe timed out.");
	}
}
