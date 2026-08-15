package com.vhmedia.livedownloader.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisualReorderFilterGraphTest {

	@Test
	void buildsVideoOnlyTrimAndConcatInVisualOrder() {
		List<EditorSegment> visual = List.of(
				seg("C", 2000, 3000),
				seg("A", 0, 1000),
				seg("D", 3000, 4000),
				seg("B", 1000, 2000)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0);

		assertThat(graph).contains("[0:v]trim=start=2.000:end=3.000,setpts=PTS-STARTPTS[v0]");
		assertThat(graph).contains("[0:v]trim=start=0.000:end=1.000,setpts=PTS-STARTPTS[v1]");
		assertThat(graph).contains("[0:v]trim=start=3.000:end=4.000,setpts=PTS-STARTPTS[v2]");
		assertThat(graph).contains("[0:v]trim=start=1.000:end=2.000,setpts=PTS-STARTPTS[v3]");
		assertThat(graph).contains("[v0][v1][v2][v3]concat=n=4:v=1:a=0[vcat]");
		assertThat(graph).doesNotContain("atrim");
		assertThat(graph).doesNotContain("[0:a]");
		assertThat(graph).doesNotContain(":a=1");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(graph)).isFalse();
	}

	@Test
	void specExampleCadbTrimsSourceRangesInVisualOrderWithoutAudio() {
		List<EditorSegment> visual = List.of(
				seg("C", 25_000, 40_000),
				seg("A", 0, 10_000),
				seg("D", 40_000, 60_000),
				seg("B", 10_000, 25_000)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0);

		assertThat(graph).contains("[0:v]trim=start=25.000:end=40.000,setpts=PTS-STARTPTS[v0]");
		assertThat(graph).contains("[0:v]trim=start=0.000:end=10.000,setpts=PTS-STARTPTS[v1]");
		assertThat(graph).contains("[0:v]trim=start=40.000:end=60.000,setpts=PTS-STARTPTS[v2]");
		assertThat(graph).contains("[0:v]trim=start=10.000:end=25.000,setpts=PTS-STARTPTS[v3]");
		assertThat(graph).contains("[v0][v1][v2][v3]concat=n=4:v=1:a=0[vcat]");
		assertThat(graph).doesNotContain("atrim");
		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(60_000L);
		assertThat(VisualReorderFilterGraph.describe(visual))
				.isEqualTo("C[25000-40000] | A[0-10000] | D[40000-60000] | B[10000-25000]");
	}

	@Test
	void appendsScaleAndFpsWithoutTouchingAudio() {
		List<EditorSegment> visual = List.of(seg("A", 0, 1000), seg("B", 1000, 2000));
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				60.0d,
				new EditorExportSettings(EditorExportFps.FPS_24, EditorExportResolution.P720, EditorExportCodec.H264)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0, plan);

		assertThat(graph).contains("[0:v]trim=start=0.000:end=1.000,setpts=PTS-STARTPTS,scale=1280:720:force_original_aspect_ratio=decrease");
		assertThat(graph).contains("pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=24,format=yuv420p");
		assertThat(graph).contains("[v0][v1]concat=n=2:v=1:a=0[vcat]");
		assertThat(graph).doesNotContain("scale=1280:720,");
		assertThat(graph).doesNotContain("atrim");
		assertThat(graph).doesNotContain("atempo");
		assertThat(graph).doesNotContain("asetrate");
		assertThat(graph).doesNotContain("[0:a]");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(graph)).isFalse();
	}

	@Test
	void letterboxNeverStretches() {
		assertThat(VisualReorderFilterGraph.fitCanvas(1080, 1920, 30.0d))
				.isEqualTo("scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=30,format=yuv420p,setpts=PTS-STARTPTS");
	}

	@Test
	void padsWhenVisualIsShorterThanSource() {
		List<EditorSegment> visual = List.of(seg("A", 0, 1000));
		String graph = VisualReorderFilterGraph.build(visual, 250);
		assertThat(graph).contains("tpad=stop_mode=clone:stop_duration=0.250[vout]");
	}

	@Test
	void visualDurationSumsSegmentLengths() {
		List<EditorSegment> visual = List.of(seg("A", 0, 1000), seg("B", 1000, 2500));
		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(2500L);
	}

	@Test
	void fps25IsAppliedOnVisualGraphOnly() {
		List<EditorSegment> visual = List.of(seg("A", 0, 1000), seg("B", 1000, 2000));
		EditorExportPlan plan = new EditorExportPlanner().plan(
				320,
				240,
				30.0d,
				new EditorExportSettings(EditorExportFps.FPS_25, EditorExportResolution.ORIGINAL, EditorExportCodec.H264)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0, plan);

		assertThat(plan.outputFps()).isEqualTo(25.0d);
		assertThat(graph).contains("fps=25");
		assertThat(graph).doesNotContain("atempo");
		assertThat(graph).doesNotContain("asetrate");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(graph)).isFalse();
	}

	@Test
	void imageClipUsesExtraInputAndNormalizesCanvasWithoutAudio() {
		UUID assetId = UUID.randomUUID();
		Path image = Path.of("still.png");
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				30.0d,
				EditorExportSettings.defaults()
		);
		List<EditorSegment> visual = List.of(
				seg("C", 25_000, 40_000),
				seg("A", 0, 10_000),
				seg("D", 40_000, 60_000),
				EditorSegment.image("img", "IMG", assetId, 15_000)
		);

		VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
				visual,
				java.util.Map.of(assetId, image),
				0,
				plan
		);

		assertThat(compiled.imageInputs()).hasSize(1);
		assertThat(compiled.imageInputs().getFirst().inputIndex()).isEqualTo(1);
		assertThat(compiled.imageInputs().getFirst().durationMillis()).isEqualTo(15_000);
		assertThat(compiled.filterComplex()).contains("[0:v]trim=start=25.000:end=40.000");
		assertThat(compiled.filterComplex()).contains("[1:v]scale=1920:1080:force_original_aspect_ratio=decrease");
		assertThat(compiled.filterComplex()).contains("format=yuv420p");
		assertThat(compiled.filterComplex()).contains("fps=30");
		assertThat(compiled.filterComplex()).contains("[v0][v1][v2][v3]concat=n=4:v=1:a=0[vcat]");
		assertThat(compiled.filterComplex()).doesNotContain("atrim");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(compiled.filterComplex())).isFalse();
	}

	@Test
	void onePointFiveXTrimsResetsPtsSpeedsAndMatchesExpectedOutputDuration() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 5_000, 10_000, null, 1.5d)
		);

		VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
				visual,
				Map.of(),
				0,
				null
		);

		assertThat(compiled.filterComplex())
				.contains("[0:v]trim=start=5.000:end=10.000,setpts=(PTS-STARTPTS)/1.500,settb=AVTB[v0]");
		assertThat(compiled.filterComplex()).doesNotContain("atempo");
		assertThat(compiled.filterComplex()).doesNotContain("asetrate");
		assertThat(compiled.expectedOutputDurationMillis()).isEqualTo(3_333L);
		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(3_333L);
		assertThat(visual.getFirst().sourceDurationMillis()).isEqualTo(5_000L);
		assertThat(visual.getFirst().durationMillis()).isEqualTo(3_333L);
	}

	@Test
	void fourSecondClipDurationsFollowPlaybackRateWithoutAudioFilters() {
		List<EditorSegment> oneX = List.of(EditorSegment.video("a", "A", 0, 4_000));
		List<EditorSegment> twoX = List.of(EditorSegment.video("a", "A", 0, 4_000, null, 2.0d));
		List<EditorSegment> halfX = List.of(EditorSegment.video("a", "A", 0, 4_000, null, 0.5d));

		assertThat(VisualReorderFilterGraph.visualDurationMillis(oneX)).isEqualTo(4_000L);
		assertThat(VisualReorderFilterGraph.visualDurationMillis(twoX)).isEqualTo(2_000L);
		assertThat(VisualReorderFilterGraph.visualDurationMillis(halfX)).isEqualTo(8_000L);
		assertThat(VisualReorderFilterGraph.build(twoX, 0)).contains("setpts=(PTS-STARTPTS)/2.000");
		assertThat(VisualReorderFilterGraph.build(halfX, 0)).contains("setpts=(PTS-STARTPTS)/0.500");
		assertThat(VisualReorderFilterGraph.build(twoX, 0)).doesNotContain("atempo");
		assertThat(VisualReorderFilterGraph.build(halfX, 0)).doesNotContain("asetrate");
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming(VisualReorderFilterGraph.build(twoX, 0))).isFalse();
	}

	@Test
	void imageClipIgnoresPlaybackRateAndUsesDurationSlot() {
		UUID assetId = UUID.randomUUID();
		Path image = Path.of("still.png");
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				30.0d,
				EditorExportSettings.defaults()
		);
		EditorSegment still = EditorSegment.image("img", "IMG", assetId, 8_000, 5_000L, 10_000L);
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 5_000, null, 1.5d),
				still
		);

		VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
				visual,
				java.util.Map.of(assetId, image),
				0,
				plan
		);

		assertThat(still.playbackRate()).isEqualTo(1.0d);
		assertThat(still.durationMillis()).isEqualTo(8_000L);
		assertThat(compiled.imageInputs().getFirst().durationMillis()).isEqualTo(8_000L);
		assertThat(compiled.filterComplex()).contains("[0:v]trim=start=0.000:end=5.000,setpts=(PTS-STARTPTS)/1.500,settb=AVTB");
		assertThat(compiled.filterComplex()).contains("[1:v]scale=");
		assertThat(compiled.filterComplex()).doesNotContain("[1:v]trim=");
		assertThat(compiled.filterComplex()).doesNotContain("[1:v]setpts=(PTS-STARTPTS)/");
		assertThat(compiled.expectedOutputDurationMillis()).isEqualTo(3_333L + 8_000L);
		assertThat(compiled.filterComplex()).contains("concat=n=2:v=1:a=0[vcat]");
	}

	@Test
	void twoXUsesSetPtsDivisorWithoutAudioFilters() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 10_000, 20_000, null, 2.0d)
		);

		VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
				visual,
				Map.of(),
				0,
				null
		);

		assertThat(compiled.filterComplex())
				.contains("[0:v]trim=start=10.000:end=20.000,setpts=(PTS-STARTPTS)/2.000,settb=AVTB[v0]");
		assertThat(compiled.filterComplex()).contains("setsar=1,format=yuv420p[vout]");
		assertThat(compiled.filterComplex()).doesNotContain("atempo");
		assertThat(compiled.filterComplex()).doesNotContain("asetrate");
		assertThat(compiled.filterComplex()).doesNotContain("atrim");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(compiled.filterComplex())).isFalse();
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming(compiled.filterComplex())).isFalse();
		assertThat(compiled.expectedOutputDurationMillis()).isEqualTo(5_000L);
		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(5_000L);
	}

	@Test
	void concatExpectedDurationEqualsSumOfClipVisualDurations() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video("a", "A", 0, 5_000, null, 1.5d),
				EditorSegment.video("b", "B", 5_000, 10_000, null, 1.0d)
		);
		VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
				visual,
				Map.of(),
				0,
				null
		);
		long expected = 3_333L + 5_000L;
		assertThat(compiled.expectedOutputDurationMillis()).isEqualTo(expected);
		assertThat(compiled.filterComplex()).contains("[v0][v1]concat=n=2:v=1:a=0[vcat]");
	}

	@Test
	void halfSpeedUsesSetPtsDivisorWithoutAudioFilters() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 10_000, null, 0.5d)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0);

		assertThat(graph).contains("setpts=(PTS-STARTPTS)/0.500,settb=AVTB[v0]");
		assertThat(graph).contains("setsar=1,format=yuv420p[vout]");
		assertThat(VisualReorderFilterGraph.containsAudioFilters(graph)).isFalse();
		assertThat(VisualReorderFilterGraph.visualDurationMillis(visual)).isEqualTo(20_000L);
	}

	@Test
	void spedClipOnCanvasNormalizesResolutionFpsSarAndPixelFormat() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 10_000, null, 2.0d),
				EditorSegment.video(UUID.randomUUID().toString(), "B", 10_000, 20_000, null, 1.0d)
		);
		EditorExportPlan plan = new EditorExportPlanner().plan(
				1920,
				1080,
				30.0d,
				new EditorExportSettings(EditorExportFps.FPS_24, EditorExportResolution.P720, EditorExportCodec.H264)
		);

		String graph = VisualReorderFilterGraph.build(visual, 0, plan);

		assertThat(graph).contains("trim=start=0.000:end=10.000,setpts=(PTS-STARTPTS)/2.000,settb=AVTB");
		assertThat(graph).contains("scale=1280:720:force_original_aspect_ratio=decrease");
		assertThat(graph).contains("setsar=1");
		assertThat(graph).contains("fps=24");
		assertThat(graph).contains("format=yuv420p");
		assertThat(graph).contains("[v0][v1]concat=n=2:v=1:a=0[vcat]");
		assertThat(graph).doesNotContain(":a=1");
		assertThat(graph).doesNotContain("atempo");
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming(graph)).isFalse();
	}

	@Test
	void flagsForbiddenAudioRetimingButAllowsLockedAtrimOnCombinedGraph() {
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming("atempo=2.0")).isTrue();
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming("asetrate=48000")).isTrue();
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming("concat=n=2:v=1:a=1[out]")).isTrue();
		assertThat(VisualReorderFilterGraph.containsForbiddenAudioRetiming(
				"[0:a]atrim=start=0:end=25.000,asetpts=PTS-STARTPTS[aout]"
		)).isFalse();
	}

	private static EditorSegment seg(String label, long start, long end) {
		return new EditorSegment(UUID.randomUUID().toString(), label, start, end);
	}
}
