package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.EditorSegmentValidator;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.support.FfmpegTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Optional local FFmpeg integration: A→B→C→D source, C→A→D→B visual reorder,
 * original audio timeline unchanged. Skipped when ffmpeg/ffprobe are not on PATH.
 */
class FfmpegVisualReorderIT {

	private static final int WIDTH = 320;
	private static final int HEIGHT = 240;
	private static final int FPS = 25;
	private static final int SOURCE_SECONDS = 4;
	private static final long SOURCE_MILLIS = SOURCE_SECONDS * 1000L;
	private static final long DURATION_SLACK_MILLIS = 250L;
	private static final long TRIM_SOURCE_MILLIS = 27_167L;
	private static final long TRIM_OUTPUT_MILLIS = 25_000L;

	@TempDir
	Path tempDir;

	private FfmpegVisualReorderService reorderService;
	private FfprobeService ffprobeService;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setFfmpegPath("ffmpeg");
		mediaProperties.setFfprobePath("ffprobe");
		mediaProperties.setRecordingsDirectory(tempDir.toString());
		EditorProperties editorProperties = new EditorProperties();
		editorProperties.setExportTimeoutMinutes(2);
		reorderService = new FfmpegVisualReorderService(
				mediaProperties,
				editorProperties,
				Mockito.mock(ApplicationEventPublisher.class)
		);
		ffprobeService = new FfprobeService(mediaProperties, new StreamProbeParser(JsonMapper.builder().build()));
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isMediaToolsAvailable")
	void reordersCadbVisualWhileKeepingOriginalAudioTimeline() throws Exception {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("export.mp4");
		generateAbcdColorScenes(source);

		assertThat(classifyScene(source, 0.5)).isEqualTo(Scene.RED);
		assertThat(classifyScene(source, 1.5)).isEqualTo(Scene.GREEN);
		assertThat(classifyScene(source, 2.5)).isEqualTo(Scene.BLUE);
		assertThat(classifyScene(source, 3.5)).isEqualTo(Scene.YELLOW);

		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "C", 2000, 3000),
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000),
				new EditorSegment(UUID.randomUUID().toString(), "D", 3000, 4000),
				new EditorSegment(UUID.randomUUID().toString(), "B", 1000, 2000)
		);

		long size = reorderService.render(
				UUID.randomUUID(),
				source,
				output,
				visual,
				SOURCE_MILLIS,
				true,
				"aac"
		);

		assertThat(Files.exists(output)).isTrue();
		assertThat(size).isPositive();
		assertThat(Files.size(output)).isEqualTo(size);

		StreamProbeResult probe = ffprobeService.probeLocalFile(output);
		assertThat(probe.isHasVideo()).isTrue();
		assertThat(probe.isHasAudio()).isTrue();
		assertThat(probe.getVideoCodec()).containsIgnoringCase("264");
		assertThat(probe.getWidth()).isEqualTo(WIDTH);
		assertThat(probe.getHeight()).isEqualTo(HEIGHT);
		assertThat(probe.getFps()).isCloseTo(FPS, within(0.05));
		assertThat(probe.getDurationMillis()).isNotNull();
		assertThat(probe.getDurationMillis()).isCloseTo(SOURCE_MILLIS, within(DURATION_SLACK_MILLIS));
		assertThat(probe.getAudioCodec()).isNotBlank();

		StreamProbeResult sourceProbe = ffprobeService.probeLocalFile(source);
		assertThat(sourceProbe.getDurationMillis()).isNotNull();
		assertThat(probe.getDurationMillis()).isCloseTo(sourceProbe.getDurationMillis(), within(DURATION_SLACK_MILLIS));

		assertThat(classifyScene(output, 0.5)).isEqualTo(Scene.BLUE);
		assertThat(classifyScene(output, 1.5)).isEqualTo(Scene.RED);
		assertThat(classifyScene(output, 2.5)).isEqualTo(Scene.YELLOW);
		assertThat(classifyScene(output, 3.5)).isEqualTo(Scene.GREEN);

		byte[] originalPcm = extractStablePcmPrefix(source);
		byte[] outputPcm = extractStablePcmPrefix(output);
		assertThat(originalPcm.length).isGreaterThan(200_000);
		assertThat(outputPcm).hasSameSizeAs(originalPcm);
		assertThat(md5(outputPcm)).isEqualTo(md5(originalPcm));
		assertThat(md5(extractPcmWindow(output, 0.2, 0.6))).isEqualTo(md5(extractPcmWindow(source, 0.2, 0.6)));
		assertThat(md5(extractPcmWindow(output, 1.2, 0.6))).isEqualTo(md5(extractPcmWindow(source, 1.2, 0.6)));
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isMediaToolsAvailable")
	void trimsLockedAudioToOutputDurationWithoutAtempo() throws Exception {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("trimmed.mp4");
		generateAbcdColorScenes(source);

		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000),
				new EditorSegment(UUID.randomUUID().toString(), "B", 1000, 3000)
		);

		reorderService.acquireExportSlot();
		long size;
		try {
			size = reorderService.renderWithAcquiredSlot(
					UUID.randomUUID(),
					null,
					source,
					output,
					visual,
					3_000L,
					SOURCE_MILLIS,
					true,
					"aac",
					null,
					Map.of()
			);
		} finally {
			reorderService.releaseExportSlot();
		}

		assertThat(size).isPositive();
		StreamProbeResult probe = ffprobeService.probeLocalFile(output);
		assertThat(probe.isHasVideo()).isTrue();
		assertThat(probe.isHasAudio()).isTrue();
		assertThat(probe.getDurationMillis()).isCloseTo(3_000L, within(DURATION_SLACK_MILLIS));
		assertThat(classifyScene(output, 0.5)).isEqualTo(Scene.RED);
		assertThat(classifyScene(output, 1.5)).isEqualTo(Scene.GREEN);
		assertThat(classifyScene(output, 2.5)).isEqualTo(Scene.BLUE);
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isMediaToolsAvailable")
	void trimsTwentySevenSecondsSourceToTwentyFiveSecondsOutput() throws Exception {
		Path source = tempDir.resolve("source-27s.mp4");
		Path output = tempDir.resolve("trimmed-25s.mp4");
		generateTimedColorAndTone(source, TRIM_SOURCE_MILLIS);

		StreamProbeResult sourceProbe = ffprobeService.probeLocalFile(source);
		assertThat(sourceProbe.isHasVideo()).isTrue();
		assertThat(sourceProbe.isHasAudio()).isTrue();
		assertThat(sourceProbe.getDurationMillis()).isNotNull();
		assertThat(sourceProbe.getDurationMillis()).isCloseTo(TRIM_SOURCE_MILLIS, within(DURATION_SLACK_MILLIS));

		EditorProperties properties = new EditorProperties();
		properties.setMinSegmentMillis(100);
		properties.setCoverageEpsilonMillis(50);
		EditorSegmentValidator validator = new EditorSegmentValidator(properties);
		long sourceMs = sourceProbe.getDurationMillis();
		List<EditorSegment> visual = validator.cropToOutputRange(
				List.of(EditorSegment.video(UUID.randomUUID().toString(), "A", 0, sourceMs)),
				0L,
				TRIM_OUTPUT_MILLIS
		);
		assertThat(EditorTimelineDurations.outputDurationMillis(visual)).isEqualTo(TRIM_OUTPUT_MILLIS);

		reorderService.acquireExportSlot();
		long size;
		try {
			size = reorderService.renderWithAcquiredSlot(
					UUID.randomUUID(),
					null,
					source,
					output,
					visual,
					TRIM_OUTPUT_MILLIS,
					sourceMs,
					true,
					"aac",
					null,
					Map.of()
			);
		} finally {
			reorderService.releaseExportSlot();
		}

		assertThat(size).isPositive();
		assertThat(Files.exists(output)).isTrue();

		StreamProbeResult probe = ffprobeService.probeLocalFile(output);
		assertThat(probe.isHasVideo()).isTrue();
		assertThat(probe.isHasAudio()).isTrue();
		assertThat(probe.getDurationMillis()).isNotNull().isCloseTo(TRIM_OUTPUT_MILLIS, within(DURATION_SLACK_MILLIS));
		assertThat(probe.getVideoDurationMillis()).isNotNull().isCloseTo(TRIM_OUTPUT_MILLIS, within(DURATION_SLACK_MILLIS));
		assertThat(probe.getAudioDurationMillis()).isNotNull().isCloseTo(TRIM_OUTPUT_MILLIS, within(DURATION_SLACK_MILLIS));
		assertThat(probe.getDurationMillis()).isLessThan(sourceProbe.getDurationMillis());
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isMediaToolsAvailable")
	void speedsFourSecondClipTwoXWhileKeepingOriginalAudioPitch() throws Exception {
		Path source = tempDir.resolve("source-4s.mp4");
		Path output = tempDir.resolve("sped-2s.mp4");
		generateAbcdColorScenes(source);

		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, SOURCE_MILLIS, null, 2.0d)
		);
		assertThat(EditorTimelineDurations.outputDurationMillis(visual)).isEqualTo(2_000L);

		reorderService.acquireExportSlot();
		try {
			reorderService.renderWithAcquiredSlot(
					UUID.randomUUID(),
					null,
					source,
					output,
					visual,
					2_000L,
					SOURCE_MILLIS,
					true,
					"aac",
					null,
					Map.of()
			);
		} finally {
			reorderService.releaseExportSlot();
		}

		StreamProbeResult probe = ffprobeService.probeLocalFile(output);
		assertThat(probe.isHasVideo()).isTrue();
		assertThat(probe.isHasAudio()).isTrue();
		assertThat(probe.getDurationMillis()).isCloseTo(2_000L, within(DURATION_SLACK_MILLIS));
		assertThat(probe.getAudioDurationMillis()).isNotNull().isCloseTo(2_000L, within(DURATION_SLACK_MILLIS));
	}

	@Test
	@EnabledIf("com.vhmedia.livedownloader.support.FfmpegTestSupport#isMediaToolsAvailable")
	void halfSpeedFourSecondClipKeepsOriginalAudioPrefix() throws Exception {
		Path source = tempDir.resolve("source-8s.mp4");
		Path output = tempDir.resolve("slow-8s.mp4");
		generateTimedColorAndPatternedTones(source, 8);

		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 4_000, null, 0.5d)
		);
		assertThat(EditorTimelineDurations.outputDurationMillis(visual)).isEqualTo(8_000L);

		reorderService.acquireExportSlot();
		try {
			reorderService.renderWithAcquiredSlot(
					UUID.randomUUID(),
					null,
					source,
					output,
					visual,
					8_000L,
					8_000L,
					true,
					"aac",
					null,
					Map.of()
			);
		} finally {
			reorderService.releaseExportSlot();
		}

		StreamProbeResult probe = ffprobeService.probeLocalFile(output);
		assertThat(probe.getDurationMillis()).isCloseTo(8_000L, within(DURATION_SLACK_MILLIS));
		byte[] originalPcm = extractPcmWindow(source, 0.0, 7.0);
		byte[] outputPcm = extractPcmWindow(output, 0.0, 7.0);
		assertThat(outputPcm).hasSameSizeAs(originalPcm);
		assertThat(md5(outputPcm)).isEqualTo(md5(originalPcm));
		assertThat(md5(extractPcmWindow(output, 0.2, 0.6))).isEqualTo(md5(extractPcmWindow(source, 0.2, 0.6)));
		assertThat(md5(extractPcmWindow(output, 4.2, 0.6))).isEqualTo(md5(extractPcmWindow(source, 4.2, 0.6)));
	}

	/**
	 * Four 1s solid-color scenes plus a 440 Hz sine, 4s total. Distinct colors make CADB reorder detectable.
	 */
	private static void generateAbcdColorScenes(Path output) throws Exception {
		FfmpegTestSupport.run(List.of(
				"ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
				"-f", "lavfi", "-i", colorInput("red"),
				"-f", "lavfi", "-i", colorInput("green"),
				"-f", "lavfi", "-i", colorInput("blue"),
				"-f", "lavfi", "-i", colorInput("yellow"),
				"-f", "lavfi", "-i", "sine=frequency=220:sample_rate=44100:duration=1",
				"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1",
				"-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=1",
				"-f", "lavfi", "-i", "sine=frequency=1760:sample_rate=44100:duration=1",
				"-filter_complex", "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v];[4:a][5:a][6:a][7:a]concat=n=4:v=0:a=1[a]",
				"-map", "[v]", "-map", "[a]",
				"-c:v", "libx264", "-preset", "ultrafast", "-tune", "stillimage",
				"-pix_fmt", "yuv420p", "-g", "25", "-keyint_min", "25", "-sc_threshold", "0",
				"-c:a", "aac", "-b:a", "128k",
				"-t", String.valueOf(SOURCE_SECONDS),
				"-y", FfmpegTestSupport.quote(output)
		));
	}

	private static void generateTimedColorAndTone(Path output, long durationMillis) throws Exception {
		String seconds = String.format(java.util.Locale.ROOT, "%.3f", durationMillis / 1000.0d);
		FfmpegTestSupport.run(List.of(
				"ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
				"-f", "lavfi", "-i", "color=c=blue:s=" + WIDTH + "x" + HEIGHT + ":r=" + FPS + ":d=" + seconds,
				"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=" + seconds,
				"-c:v", "libx264", "-preset", "ultrafast", "-tune", "stillimage",
				"-pix_fmt", "yuv420p", "-g", String.valueOf(FPS), "-keyint_min", String.valueOf(FPS),
				"-sc_threshold", "0",
				"-c:a", "aac", "-b:a", "128k",
				"-t", seconds,
				"-y", FfmpegTestSupport.quote(output)
		), 120);
	}

	private static void generateTimedColorAndPatternedTones(Path output, int seconds) throws Exception {
		int[] freqs = {220, 440, 880, 1760};
		List<String> command = new java.util.ArrayList<>(List.of(
				"ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
				"-f", "lavfi", "-i", "color=c=blue:s=" + WIDTH + "x" + HEIGHT + ":r=" + FPS + ":d=" + seconds
		));
		StringBuilder audioConcat = new StringBuilder();
		for (int i = 0; i < seconds; i++) {
			command.addAll(List.of(
					"-f", "lavfi", "-i",
					"sine=frequency=" + freqs[i % freqs.length] + ":sample_rate=44100:duration=1"
			));
			audioConcat.append('[').append(i + 1).append(":a]");
		}
		audioConcat.append("concat=n=").append(seconds).append(":v=0:a=1[a]");
		command.addAll(List.of(
				"-filter_complex", audioConcat.toString(),
				"-map", "0:v", "-map", "[a]",
				"-c:v", "libx264", "-preset", "ultrafast", "-tune", "stillimage",
				"-pix_fmt", "yuv420p", "-g", String.valueOf(FPS), "-keyint_min", String.valueOf(FPS),
				"-sc_threshold", "0",
				"-c:a", "aac", "-b:a", "128k",
				"-t", String.valueOf(seconds),
				"-y", FfmpegTestSupport.quote(output)
		));
		FfmpegTestSupport.run(command, 120);
	}

	private static String colorInput(String name) {
		return "color=c=" + name + ":s=" + WIDTH + "x" + HEIGHT + ":r=" + FPS + ":d=1";
	}

	private static Scene classifyScene(Path video, double atSeconds) throws Exception {
		byte[] rgb = FfmpegTestSupport.runAndCaptureStdout(List.of(
				"ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
				"-i", FfmpegTestSupport.quote(video),
				"-ss", String.format(java.util.Locale.ROOT, "%.3f", atSeconds),
				"-frames:v", "1",
				"-vf", "scale=8:8",
				"-f", "rawvideo",
				"-pix_fmt", "rgb24",
				"pipe:1"
		));
		assertThat(rgb).hasSize(8 * 8 * 3);
		long r = 0;
		long g = 0;
		long b = 0;
		int pixels = rgb.length / 3;
		for (int i = 0; i < rgb.length; i += 3) {
			r += rgb[i] & 0xFF;
			g += rgb[i + 1] & 0xFF;
			b += rgb[i + 2] & 0xFF;
		}
		r /= pixels;
		g /= pixels;
		b /= pixels;
		if (r > 80 && g > 80 && b < 90 && (r + g) > b * 3) {
			return Scene.YELLOW;
		}
		if (r > g + 40 && r > b + 40) {
			return Scene.RED;
		}
		if (g > r + 40 && g > b + 40) {
			return Scene.GREEN;
		}
		if (b > r + 40 && b > g + 40) {
			return Scene.BLUE;
		}
		throw new AssertionError("Could not classify scene at " + atSeconds + "s rgb=" + r + "," + g + "," + b);
	}

	/**
	 * Decode a fixed prefix so AAC encoder delay at EOF cannot flake the checksum.
	 * Patterned tones still fail this hash if audio were reordered or re-timed.
	 */
	private static byte[] extractStablePcmPrefix(Path input) throws Exception {
		return extractPcmWindow(input, 0.0, 3.5);
	}

	private static byte[] extractPcmWindow(Path input, double startSeconds, double durationSeconds) throws Exception {
		return FfmpegTestSupport.runAndCaptureStdout(List.of(
				"ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
				"-i", FfmpegTestSupport.quote(input),
				"-ss", String.format(java.util.Locale.ROOT, "%.3f", startSeconds),
				"-vn", "-t", String.format(java.util.Locale.ROOT, "%.3f", durationSeconds),
				"-ac", "1", "-ar", "44100",
				"-f", "s16le", "-c:a", "pcm_s16le",
				"pipe:1"
		));
	}

	private static String md5(byte[] data) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("MD5");
		digest.update(data);
		return HexFormat.of().formatHex(digest.digest());
	}

	private enum Scene {
		RED, GREEN, BLUE, YELLOW
	}
}
