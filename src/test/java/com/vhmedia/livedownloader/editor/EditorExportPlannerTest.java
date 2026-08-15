package com.vhmedia.livedownloader.editor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EditorExportPlannerTest {

	private final EditorExportPlanner planner = new EditorExportPlanner();

	@Test
	void originalPortraitKeepsVerticalCanvas() {
		EditorExportPlan plan = planner.plan(
				1080,
				1920,
				30.0d,
				EditorExportSettings.defaults()
		);

		assertThat(plan.scale()).isFalse();
		assertThat(plan.outputWidth()).isEqualTo(1080);
		assertThat(plan.outputHeight()).isEqualTo(1920);
		assertThat(plan.outputFps()).isEqualTo(30.0d);

		String graph = VisualReorderFilterGraph.build(List.of(
				new EditorSegment(UUID.randomUUID().toString(), "A", 0, 1000)
		), 0, plan);
		assertThat(graph).contains("scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1");
		assertThat(graph).contains("format=yuv420p");
	}

	@Test
	void originalKeepsEvenSourceSizeAndFps() {
		EditorExportPlan plan = planner.plan(
				1280,
				720,
				30.0d,
				EditorExportSettings.defaults()
		);

		assertThat(plan.scale()).isFalse();
		assertThat(plan.changeFps()).isFalse();
		assertThat(plan.outputWidth()).isEqualTo(1280);
		assertThat(plan.outputHeight()).isEqualTo(720);
		assertThat(plan.outputFps()).isEqualTo(30.0d);
		assertThat(plan.settings().codec()).isEqualTo(EditorExportCodec.H264);
	}

	@Test
	void portrait1080pUses1080x1920Canvas() {
		EditorExportPlan plan = planner.plan(
				1440,
				2560,
				30.0d,
				new EditorExportSettings(EditorExportFps.FPS_30, EditorExportResolution.P1080, EditorExportCodec.H264)
		);

		assertThat(plan.scale()).isTrue();
		assertThat(plan.changeFps()).isTrue();
		assertThat(plan.outputWidth()).isEqualTo(1080);
		assertThat(plan.outputHeight()).isEqualTo(1920);
		assertThat(plan.outputFps()).isEqualTo(30.0d);
	}

	@Test
	void landscape1080pUses1920x1080Canvas() {
		EditorExportPlan plan = planner.plan(
				3840,
				2160,
				30.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P1080, EditorExportCodec.H264)
		);
		assertThat(plan.outputWidth()).isEqualTo(1920);
		assertThat(plan.outputHeight()).isEqualTo(1080);
		assertThat(plan.outputFps()).isEqualTo(30.0d);
		assertThat(plan.changeFps()).isFalse();
	}

	@Test
	void landscape720pUses1280x720Canvas() {
		EditorExportPlan plan = planner.plan(
				1920,
				1080,
				60.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P720, EditorExportCodec.H264)
		);

		assertThat(plan.outputWidth()).isEqualTo(1280);
		assertThat(plan.outputHeight()).isEqualTo(720);
		assertThat(plan.changeFps()).isFalse();
		assertThat(plan.outputFps()).isEqualTo(60.0d);
	}

	@Test
	void portrait720pUses720x1280Canvas() {
		EditorExportPlan plan = planner.plan(
				1080,
				1920,
				30.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P720, EditorExportCodec.H264)
		);
		assertThat(plan.outputWidth()).isEqualTo(720);
		assertThat(plan.outputHeight()).isEqualTo(1280);
	}

	@Test
	void landscape540pUses960x540Canvas() {
		EditorExportPlan plan = planner.plan(
				1920,
				1080,
				25.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P540, EditorExportCodec.H264)
		);
		assertThat(plan.outputWidth()).isEqualTo(960);
		assertThat(plan.outputHeight()).isEqualTo(540);
	}

	@Test
	void portrait540pUses540x960Canvas() {
		EditorExportPlan plan = planner.plan(
				1080,
				1920,
				25.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P540, EditorExportCodec.H264)
		);
		assertThat(plan.outputWidth()).isEqualTo(540);
		assertThat(plan.outputHeight()).isEqualTo(960);
	}

	@Test
	void selectedFpsIsVisualOnlyAndDoesNotChangeWhenOriginal() {
		EditorExportPlan original = planner.plan(
				1280,
				720,
				29.97d,
				EditorExportSettings.defaults()
		);
		assertThat(original.changeFps()).isFalse();
		assertThat(original.outputFps()).isEqualTo(29.97d);

		EditorExportPlan fps24 = planner.plan(
				1280,
				720,
				30.0d,
				new EditorExportSettings(EditorExportFps.FPS_24, EditorExportResolution.ORIGINAL, EditorExportCodec.H264)
		);
		assertThat(fps24.changeFps()).isTrue();
		assertThat(fps24.outputFps()).isEqualTo(24.0d);
		assertThat(fps24.outputWidth()).isEqualTo(1280);
		assertThat(fps24.outputHeight()).isEqualTo(720);
	}

	@Test
	void presetsNeverUpscaleSmallerSources() {
		EditorExportPlan plan = planner.plan(
				1280,
				720,
				30.0d,
				new EditorExportSettings(EditorExportFps.ORIGINAL, EditorExportResolution.P1080, EditorExportCodec.H264)
		);
		assertThat(plan.outputWidth()).isEqualTo(1280);
		assertThat(plan.outputHeight()).isEqualTo(720);
		assertThat(plan.scale()).isFalse();
	}

	@Test
	void evenRoundsOddOriginalDimensions() {
		EditorExportPlan plan = planner.plan(1281, 721, 25.0d, EditorExportSettings.defaults());
		assertThat(plan.outputWidth()).isEqualTo(1280);
		assertThat(plan.outputHeight()).isEqualTo(720);
		assertThat(plan.scale()).isTrue();
	}

	@Test
	void selectedFps25And50AreVisualOnly() {
		EditorExportPlan fps25 = planner.plan(
				1280,
				720,
				30.0d,
				new EditorExportSettings(EditorExportFps.FPS_25, EditorExportResolution.ORIGINAL, EditorExportCodec.H264)
		);
		assertThat(fps25.changeFps()).isTrue();
		assertThat(fps25.outputFps()).isEqualTo(25.0d);
		assertThat(fps25.outputWidth()).isEqualTo(1280);
		assertThat(fps25.outputHeight()).isEqualTo(720);

		EditorExportPlan fps50 = planner.plan(
				1920,
				1080,
				25.0d,
				new EditorExportSettings(EditorExportFps.FPS_50, EditorExportResolution.P720, EditorExportCodec.H264)
		);
		assertThat(fps50.changeFps()).isTrue();
		assertThat(fps50.outputFps()).isEqualTo(50.0d);
		assertThat(fps50.outputWidth()).isEqualTo(1280);
		assertThat(fps50.outputHeight()).isEqualTo(720);
	}
}
