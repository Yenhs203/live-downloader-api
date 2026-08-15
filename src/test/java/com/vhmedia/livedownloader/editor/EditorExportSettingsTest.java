package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidEditorExportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorExportSettingsTest {

	@Test
	void parsesApiValues() {
		assertThat(EditorExportFps.fromApi("24")).isEqualTo(EditorExportFps.FPS_24);
		assertThat(EditorExportFps.fromApi("FPS_24")).isEqualTo(EditorExportFps.FPS_24);
		assertThat(EditorExportFps.fromApi("25")).isEqualTo(EditorExportFps.FPS_25);
		assertThat(EditorExportFps.fromApi("FPS_25")).isEqualTo(EditorExportFps.FPS_25);
		assertThat(EditorExportFps.fromApi("30")).isEqualTo(EditorExportFps.FPS_30);
		assertThat(EditorExportFps.fromApi("FPS_30")).isEqualTo(EditorExportFps.FPS_30);
		assertThat(EditorExportFps.fromApi("50")).isEqualTo(EditorExportFps.FPS_50);
		assertThat(EditorExportFps.fromApi("60")).isEqualTo(EditorExportFps.FPS_60);
		assertThat(EditorExportFps.fromApi("FPS_60")).isEqualTo(EditorExportFps.FPS_60);
		assertThat(EditorExportFps.fromApi("ORIGINAL")).isEqualTo(EditorExportFps.ORIGINAL);
		assertThat(EditorExportQuality.fromApi("FAST")).isEqualTo(EditorExportQuality.FAST);
		assertThat(EditorExportQuality.fromApi("balanced")).isEqualTo(EditorExportQuality.BALANCED);
		assertThat(EditorExportResolution.fromApi("1080p")).isEqualTo(EditorExportResolution.P1080);
		assertThat(EditorExportCodec.fromApi("h.264")).isEqualTo(EditorExportCodec.H264);
	}

	@Test
	void mapsNumericFpsAndLeavesOriginalUnset() {
		assertThat(EditorExportFps.ORIGINAL.isOriginal()).isTrue();
		assertThat(EditorExportFps.ORIGINAL.fps()).isNull();
		assertThat(EditorExportFps.FPS_24.isOriginal()).isFalse();
		assertThat(EditorExportFps.FPS_24.fps()).isEqualTo(24.0d);
		assertThat(EditorExportFps.FPS_25.fps()).isEqualTo(25.0d);
		assertThat(EditorExportFps.FPS_30.fps()).isEqualTo(30.0d);
		assertThat(EditorExportFps.FPS_50.fps()).isEqualTo(50.0d);
		assertThat(EditorExportFps.FPS_60.fps()).isEqualTo(60.0d);
	}

	@Test
	void rejectsUnknownFps() {
		assertThatThrownBy(() -> EditorExportFps.fromApi("29.97"))
				.isInstanceOf(InvalidEditorExportException.class)
				.hasMessageContaining("24");
		assertThatThrownBy(() -> EditorExportQuality.fromApi("ultra"))
				.isInstanceOf(InvalidEditorExportException.class)
				.hasMessageContaining("BALANCED");
	}
}
