package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.support.FfmpegTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional local FFmpeg integration. Generates a tiny synthetic media file —
 * never contacts external livestream URLs (e.g. TikTok).
 */
class FfmpegSyntheticMediaIT {

	@TempDir
	Path tempDir;

	private FfmpegRemuxService remuxService;

	@BeforeEach
	void setUp() {
		MediaProperties properties = new MediaProperties();
		properties.setFfmpegPath("ffmpeg");
		remuxService = new FfmpegRemuxService(properties);
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isFfmpegAvailable")
	void remuxesSyntheticLocalMpegTsToMp4() throws Exception {
		Path inputTs = tempDir.resolve("synthetic.ts");
		Path outputMp4 = tempDir.resolve("synthetic.mp4");

		generateSyntheticTs(inputTs);
		assertThat(Files.size(inputTs)).isPositive();

		long size = remuxService.remux(inputTs, outputMp4);

		assertThat(Files.exists(outputMp4)).isTrue();
		assertThat(size).isPositive();
		assertThat(size).isEqualTo(Files.size(outputMp4));
	}

	private static void generateSyntheticTs(Path outputTs) throws Exception {
		Process process = new ProcessBuilder(
				"ffmpeg",
				"-hide_banner",
				"-loglevel",
				"error",
				"-f",
				"lavfi",
				"-i",
				"testsrc=size=160x120:rate=10",
				"-f",
				"lavfi",
				"-i",
				"sine=frequency=440:sample_rate=44100",
				"-t",
				"1",
				"-c:v",
				"libx264",
				"-pix_fmt",
				"yuv420p",
				"-c:a",
				"aac",
				"-f",
				"mpegts",
				"-y",
				outputTs.toAbsolutePath().toString()
		).redirectErrorStream(true).start();

		boolean finished = process.waitFor(60, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("ffmpeg synthetic media generation timed out");
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException("ffmpeg synthetic media generation failed with exit " + process.exitValue());
		}
	}
}
