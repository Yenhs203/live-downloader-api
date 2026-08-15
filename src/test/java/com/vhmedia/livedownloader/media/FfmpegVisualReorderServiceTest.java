package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.editor.EditorExportCodec;
import com.vhmedia.livedownloader.editor.EditorExportFps;
import com.vhmedia.livedownloader.editor.EditorExportPlan;
import com.vhmedia.livedownloader.editor.EditorExportPlanner;
import com.vhmedia.livedownloader.editor.EditorExportQuality;
import com.vhmedia.livedownloader.editor.EditorExportResolution;
import com.vhmedia.livedownloader.editor.EditorExportSettings;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.editor.VisualReorderFilterGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class FfmpegVisualReorderServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private FfmpegVisualReorderService service;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setFfmpegPath("ffmpeg");
		mediaProperties.setFfprobePath("ffprobe");
		mediaProperties.setRecordingsDirectory(tempDir.toString());
		EditorProperties editorProperties = new EditorProperties();
		service = new FfmpegVisualReorderService(mediaProperties, editorProperties, eventPublisher);
	}

	@Test
	void commandMapsOriginalAudioOutsideTheVisualFilter() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "C", 2000, 3000),
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000),
				new EditorSegment(UUID.randomUUID().toString(), "B", 1000, 2000)
		);
		String filter = VisualReorderFilterGraph.build(visual, 0);

		List<String> command = service.buildCommand(source, output, filter, 3000, true, "aac");

		assertThat(command).contains("ffmpeg", "-filter_complex", filter, "-map", "[vout]", "-map", "0:a:0?");
		assertThat(command).contains("-c:v", "libx264");
		assertThat(command).contains("-c:a", "copy");
		assertThat(command).contains("-t", "3.000");
		assertThat(command).doesNotContain("-shortest");
		int filterIndex = command.indexOf("-filter_complex");
		assertThat(command.get(filterIndex + 1)).doesNotContain("atrim");
		assertThat(command.get(filterIndex + 1)).doesNotContain("[0:a]");
		assertThat(command).doesNotContain("0:a:0");
	}

	@Test
	void commandKeepsFullSourceAudioDurationAfterCadbReorder() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "C", 25_000, 40_000),
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 10_000),
				new EditorSegment(UUID.randomUUID().toString(), "D", 40_000, 60_000),
				new EditorSegment(UUID.randomUUID().toString(), "B", 10_000, 25_000)
		);
		String filter = VisualReorderFilterGraph.build(visual, 0);

		List<String> command = service.buildCommand(source, output, filter, 60_000, true, "aac");

		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(60_000L);
		assertThat(command).contains("-map", "0:a:0?");
		assertThat(command).contains("-t", "60.000");
		assertThat(command).doesNotContain("-shortest");
		assertThat(filter).contains("trim=start=25.000:end=40.000");
		assertThat(filter).doesNotContain("atrim");
	}

	@Test
	void commandCutsAtOutputDurationWhenSourceIsLonger() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 20_000),
				new EditorSegment(UUID.randomUUID().toString(), "D", 20_000, 25_000)
		);
		String filter = VisualReorderFilterGraph.build(visual, 0);
		long outputDuration = EditorTimelineDurations.outputDurationMillis(visual);

		List<String> command = service.buildCommand(
				source,
				output,
				filter,
				outputDuration,
				27_167L,
				true,
				"aac",
				null,
				List.of()
		);

		assertThat(outputDuration).isEqualTo(25_000L);
		assertThat(command).contains("-t", "25.000");
		assertThat(command).contains("-map", "[aout]");
		assertThat(command.get(command.indexOf("-filter_complex") + 1))
				.contains("[0:a]atrim=start=0:end=25.000,asetpts=PTS-STARTPTS[aout]");
		assertThat(command).contains("-c:a", "aac", "-b:a", "192k");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command).doesNotContain("27.167");
		assertThat(command).doesNotContain("atempo");
	}

	@Test
	void commandKeepsOriginalAudioPitchWhenFourSecondClipIsSped() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> oneX = List.of(EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 4_000));
		List<EditorSegment> twoX = List.of(EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 4_000, null, 2.0d));
		List<EditorSegment> halfX = List.of(EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 4_000, null, 0.5d));

		List<String> oneXCommand = service.buildCommand(
				source, output, VisualReorderFilterGraph.build(oneX, 0), 4_000, 4_000L, true, "aac", null, List.of());
		List<String> twoXCommand = service.buildCommand(
				source, output, VisualReorderFilterGraph.build(twoX, 0), 2_000, 4_000L, true, "aac", null, List.of());
		List<String> halfXCommand = service.buildCommand(
				source, output, VisualReorderFilterGraph.build(halfX, 0), 8_000, 8_000L, true, "aac", null, List.of());

		assertThat(EditorTimelineDurations.outputDurationMillis(oneX)).isEqualTo(4_000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(twoX)).isEqualTo(2_000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(halfX)).isEqualTo(8_000L);
		assertThat(oneXCommand).contains("-t", "4.000");
		assertThat(twoXCommand).contains("-t", "2.000");
		assertThat(twoXCommand.get(twoXCommand.indexOf("-filter_complex") + 1))
				.contains("setpts=(PTS-STARTPTS)/2.000");
		assertThat(twoXCommand.get(twoXCommand.indexOf("-filter_complex") + 1))
				.contains("[0:a]atrim=start=0:end=2.000,asetpts=PTS-STARTPTS[aout]");
		assertThat(halfXCommand).contains("-t", "8.000");
		assertThat(halfXCommand.get(halfXCommand.indexOf("-filter_complex") + 1))
				.contains("setpts=(PTS-STARTPTS)/0.500");
		assertThat(oneXCommand).doesNotContain("atempo");
		assertThat(twoXCommand).doesNotContain("atempo");
		assertThat(halfXCommand).doesNotContain("atempo");
		assertThat(oneXCommand).doesNotContain("asetrate");
		assertThat(twoXCommand).doesNotContain("asetrate");
		assertThat(halfXCommand).doesNotContain("asetrate");
	}

	@Test
	void commandCutsAudioToSpedVisualDurationWithoutAtempo() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 10_000, 20_000, null, 2.0d)
		);
		String filter = VisualReorderFilterGraph.build(visual, 0);
		long outputDuration = EditorTimelineDurations.outputDurationMillis(visual);

		List<String> command = service.buildCommand(
				source,
				output,
				filter,
				outputDuration,
				20_000L,
				true,
				"aac",
				null,
				List.of()
		);

		assertThat(outputDuration).isEqualTo(5_000L);
		assertThat(filter).contains("setpts=(PTS-STARTPTS)/2.000");
		assertThat(filter).doesNotContain("atempo");
		assertThat(command.get(command.indexOf("-filter_complex") + 1))
				.contains("[0:a]atrim=start=0:end=5.000,asetpts=PTS-STARTPTS[aout]");
		assertThat(command).contains("-map", "[aout]");
		assertThat(command).contains("-c:a", "aac");
		assertThat(command).contains("-t", "5.000");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command).doesNotContain("atempo");
	}

	@Test
	void commandOmitsAudioMapWhenSourceHasNoAudio() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000)
		);
		String filter = VisualReorderFilterGraph.build(visual, 0);

		List<String> command = service.buildCommand(
				source,
				output,
				filter,
				1000,
				10_000L,
				false,
				null,
				null,
				List.of()
		);

		assertThat(command).contains("-map", "[vout]");
		assertThat(command).doesNotContain("0:a:0?");
		assertThat(command).doesNotContain("[aout]");
		assertThat(command).doesNotContain("-c:a");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command.get(command.indexOf("-filter_complex") + 1)).doesNotContain("atrim");
	}

	@Test
	void commandLoopsImageInputForDurationWithoutTouchingAudio() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		Path image = tempDir.resolve("still.png");
		UUID assetId = UUID.randomUUID();
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				30.0d,
				EditorExportSettings.defaults()
		);
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 10_000),
				EditorSegment.image("img", "IMG", assetId, 5_000)
		);
		var compiled = VisualReorderFilterGraph.compile(visual, Map.of(assetId, image), 0, plan);

		List<String> command = service.buildCommand(
				source,
				output,
				compiled.filterComplex(),
				15_000,
				true,
				"aac",
				plan,
				compiled.imageInputs()
		);

		assertThat(command).contains("-loop", "1", "-framerate", "30", "-t", "5.000", "-i", image.toAbsolutePath().toString());
		assertThat(command).contains("-map", "0:a:0?");
		assertThat(command).contains("-t", "15.000");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command.get(command.indexOf("-i") + 1)).isEqualTo(source.toAbsolutePath().toString());
		assertThat(compiled.filterComplex()).doesNotContain("atrim");
	}

	@Test
	void commandTrimsLockedAudioWhenVisualOutputIsShorterThanSourceWithImages() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		Path image = tempDir.resolve("still.png");
		UUID assetId = UUID.randomUUID();
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				30.0d,
				EditorExportSettings.defaults()
		);
		List<EditorSegment> visual = List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 10_000),
				EditorSegment.image("img", "IMG", assetId, 5_000)
		);
		var compiled = VisualReorderFilterGraph.compile(visual, Map.of(assetId, image), 0, plan);

		List<String> command = service.buildCommand(
				source,
				output,
				compiled.filterComplex(),
				15_000,
				60_000,
				true,
				"aac",
				plan,
				compiled.imageInputs()
		);

		assertThat(command).contains("-map", "[aout]");
		assertThat(command.get(command.indexOf("-filter_complex") + 1))
				.contains("[0:a]atrim=start=0:end=15.000,asetpts=PTS-STARTPTS[aout]");
		assertThat(command).contains("-c:a", "aac", "-b:a", "192k");
		assertThat(command).doesNotContain("copy");
		assertThat(command).doesNotContain("atempo");
		assertThat(compiled.filterComplex()).doesNotContain("atrim");
	}

	@Test
	void commandUsesQualityProfileInsteadOfRawClientArgs() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		EditorExportPlan fast = new EditorExportPlanner().plan(
				1280,
				720,
				30.0d,
				new EditorExportSettings(
						EditorExportFps.ORIGINAL,
						EditorExportResolution.ORIGINAL,
						EditorExportCodec.H264,
						EditorExportQuality.FAST,
						true
				)
		);
		List<EditorSegment> visual = List.of(new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000));
		String filter = VisualReorderFilterGraph.build(visual, 0, fast);

		List<String> command = service.buildCommand(source, output, filter, 1000, true, "aac", fast);

		assertThat(command).contains("-preset", "veryfast", "-crf", "26");
		assertThat(command).doesNotContain("ultrafast");
		assertThat(command).contains("-map", "0:a:0?");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command).doesNotContain("atempo");
		assertThat(command).doesNotContain("asetrate");
	}

	@Test
	void commandEncodesPcmAudioToAacForMp4() {
		Path source = tempDir.resolve("source.mp4");
		Path output = tempDir.resolve("output.mp4");
		List<EditorSegment> visual = List.of(new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000));
		String filter = VisualReorderFilterGraph.build(visual, 0);

		List<String> command = service.buildCommand(source, output, filter, 1000, true, "pcm_s16le");

		assertThat(command).contains("-map", "0:a:0?");
		assertThat(command).contains("-c:a", "aac", "-b:a", "192k");
		assertThat(command).doesNotContain("copy");
		assertThat(command).doesNotContain("-shortest");
		assertThat(command).doesNotContain("atempo");
	}

	@Test
	void requestCancelReturnsFalseWhenNothingIsRunning() {
		assertThat(service.requestCancel(UUID.randomUUID())).isFalse();
		assertThat(service.isRunning(UUID.randomUUID())).isFalse();
	}

	@Test
	void rejectsWhenConcurrentExportSlotsAreExhausted() {
		EditorProperties editorProperties = new EditorProperties();
		editorProperties.setMaxConcurrentExports(1);
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setFfmpegPath("ffmpeg");
		FfmpegVisualReorderService limited = new FfmpegVisualReorderService(
				mediaProperties,
				editorProperties,
				eventPublisher
		);

		limited.acquireExportSlot();
		assertThatThrownBy(limited::acquireExportSlot)
				.isInstanceOf(com.vhmedia.livedownloader.exception.ConcurrentEditorLimitException.class)
				.hasMessageContaining("Maximum concurrent editor exports exceeded");
		limited.releaseExportSlot();
		limited.acquireExportSlot();
		limited.releaseExportSlot();
	}
}
