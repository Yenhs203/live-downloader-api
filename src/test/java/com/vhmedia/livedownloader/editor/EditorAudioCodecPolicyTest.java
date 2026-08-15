package com.vhmedia.livedownloader.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EditorAudioCodecPolicyTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"aac",
			"AAC",
			"libfdk_aac",
			"mp4a.40.2",
			"mp3",
			"ac3",
			"eac3",
			"alac",
			"mp2"
	})
	void copiesMp4SafeCodecs(String codec) {
		assertThat(EditorAudioCodecPolicy.forMp4(codec)).isEqualTo(EditorAudioCodecPolicy.Mode.COPY);
		List<String> command = new ArrayList<>();
		EditorAudioCodecPolicy.appendMp4AudioArgs(command, true, codec);
		assertThat(command).containsExactly("-c:a", "copy");
	}

	@ParameterizedTest
	@ValueSource(strings = {"pcm_s16le", "pcm_s24le", "opus", "vorbis", "flac", "dts", "wmav2", "truehd"})
	void encodesIncompatibleCodecsAsAac(String codec) {
		assertThat(EditorAudioCodecPolicy.forMp4(codec)).isEqualTo(EditorAudioCodecPolicy.Mode.AAC);
		List<String> command = new ArrayList<>();
		EditorAudioCodecPolicy.appendMp4AudioArgs(command, true, codec);
		assertThat(command).containsExactly("-c:a", "aac", "-b:a", "192k");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"  "})
	void unknownOrBlankCodecEncodesAac(String codec) {
		assertThat(EditorAudioCodecPolicy.forMp4(codec)).isEqualTo(EditorAudioCodecPolicy.Mode.AAC);
	}

	@Test
	void filteredAudioAlwaysEncodesAacEvenWhenSourceIsCopySafe() {
		List<String> command = new ArrayList<>();
		EditorAudioCodecPolicy.appendMp4AudioArgs(command, true, "aac", true);
		assertThat(command).containsExactly("-c:a", "aac", "-b:a", "192k");
		assertThat(command).doesNotContain("copy");
	}

	@Test
	void omitsAudioArgsWhenSourceHasNoAudio() {
		List<String> command = new ArrayList<>(List.of("-map", "[vout]"));
		EditorAudioCodecPolicy.appendMp4AudioArgs(command, false, "aac");
		assertThat(command).containsExactly("-map", "[vout]");
	}

	@CsvSource({
			"aac, COPY",
			"opus, AAC"
	})
	@ParameterizedTest
	void decisionIsExplicitForCopyVersusAac(String codec, EditorAudioCodecPolicy.Mode expected) {
		assertThat(EditorAudioCodecPolicy.forMp4(codec)).isEqualTo(expected);
	}
}
