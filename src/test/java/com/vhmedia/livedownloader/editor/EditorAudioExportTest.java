package com.vhmedia.livedownloader.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditorAudioExportTest {

	@Test
	void skipsFilterWhenOutputMatchesSource() {
		assertThat(EditorAudioExport.needsFilterTrim(true, 60_000, 60_000, 50)).isFalse();
		assertThat(EditorAudioExport.needsFilterTrim(true, 59_980, 60_000, 50)).isFalse();
	}

	@Test
	void trimsWhenOutputIsShorterThanSource() {
		assertThat(EditorAudioExport.needsFilterTrim(true, 25_000, 27_167, 50)).isTrue();
		assertThat(EditorAudioExport.needsFilterTrim(true, 5_000, 10_000, 50)).isTrue();
	}

	@Test
	void neverTrimsWhenSourceHasNoAudio() {
		assertThat(EditorAudioExport.needsFilterTrim(false, 5_000, 10_000, 50)).isFalse();
	}

	@Test
	void trimFilterCutsOriginalAudioFromZero() {
		assertThat(EditorAudioExport.trimFilter(25_000))
				.isEqualTo("[0:a]atrim=start=0:end=25.000,asetpts=PTS-STARTPTS[aout]");
		assertThat(EditorAudioExport.mapLabel(true)).isEqualTo("[aout]");
		assertThat(EditorAudioExport.mapLabel(false)).isEqualTo("0:a:0?");
	}
}
