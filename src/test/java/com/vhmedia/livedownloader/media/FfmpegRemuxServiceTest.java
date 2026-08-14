package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegRemuxServiceTest {

	private FfmpegRemuxService remuxService;

	@BeforeEach
	void setUp() {
		MediaProperties properties = new MediaProperties();
		properties.setFfmpegPath("ffmpeg");
		remuxService = new FfmpegRemuxService(properties);
	}

	@Test
	void buildCommandUsesCopyMapsAndFaststart() {
		Path input = Path.of("recordings", "job.ts").toAbsolutePath();
		Path output = Path.of("recordings", "job.mp4").toAbsolutePath();

		assertThat(remuxService.buildCommand(input, output)).containsExactly(
				"ffmpeg",
				"-hide_banner",
				"-y",
				"-i",
				input.toString(),
				"-map",
				"0:v:0?",
				"-map",
				"0:a:0?",
				"-c",
				"copy",
				"-movflags",
				"+faststart",
				output.toString()
		);
	}
}
