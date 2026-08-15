package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.exception.InvalidOutputDurationException;
import com.vhmedia.livedownloader.exception.InvalidPlaybackRateException;
import com.vhmedia.livedownloader.exception.OutputDurationExceedsAudioException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorPlaybackRateTest {

	@Test
	void whitelistCanonicalizesUiValues() {
		assertThat(EditorPlaybackRate.canonicalize(0.25d)).isEqualTo(0.25d);
		assertThat(EditorPlaybackRate.canonicalize(0.5d)).isEqualTo(0.5d);
		assertThat(EditorPlaybackRate.canonicalize(2.0d)).isEqualTo(2.0d);
		assertThat(EditorPlaybackRate.canonicalize(4.0d)).isEqualTo(4.0d);
		assertThat(EditorPlaybackRate.canonicalize(null)).isEqualTo(1.0d);
	}

	@Test
	void rejectsRatesOutsideWhitelist() {
		assertThatThrownBy(() -> EditorPlaybackRate.canonicalize(1.1d))
				.isInstanceOf(InvalidPlaybackRateException.class);
		assertThatThrownBy(() -> EditorPlaybackRate.canonicalize(0.3d))
				.isInstanceOf(InvalidPlaybackRateException.class);
		assertThatThrownBy(() -> EditorPlaybackRate.canonicalize(5.0d))
				.isInstanceOf(InvalidPlaybackRateException.class);
	}

	@Test
	void visualDurationIsSourceDividedByRate() {
		assertThat(EditorPlaybackRate.visualDurationMillis(10_000, 2.0d)).isEqualTo(5_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(10_000, 0.5d)).isEqualTo(20_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(10_000, 1.0d)).isEqualTo(10_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(10_000, 1.25d)).isEqualTo(8_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(4_000, 1.0d)).isEqualTo(4_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(4_000, 2.0d)).isEqualTo(2_000L);
		assertThat(EditorPlaybackRate.visualDurationMillis(4_000, 0.5d)).isEqualTo(8_000L);
	}

	@Test
	void ffmpegLiteralMatchesSetPtsDivisor() {
		assertThat(EditorPlaybackRate.ffmpegLiteral(2.0d)).isEqualTo("2.000");
		assertThat(EditorPlaybackRate.ffmpegLiteral(0.25d)).isEqualTo("0.250");
		assertThat(EditorPlaybackRate.ffmpegLiteral(0.5d)).isEqualTo("0.500");
		assertThat(EditorPlaybackRate.ffmpegLiteral(1.25d)).isEqualTo("1.250");
	}

	@Test
	void lockedAudioRejectsSlowMotionPastSource() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 0, 10_000, null, 0.5d)
		);
		assertThat(EditorTimelineDurations.outputDurationMillis(visual)).isEqualTo(20_000L);
		assertThatThrownBy(() -> EditorTimelineDurations.assertFitsLockedAudio(true, 10_000L, visual))
				.isInstanceOf(OutputDurationExceedsAudioException.class);
		EditorTimelineDurations.assertFitsLockedAudio(false, 10_000L, visual);
	}

	@Test
	void outputDurationMustBePositive() {
		assertThatThrownBy(() -> EditorTimelineDurations.assertFitsLockedAudio(false, 10_000L, List.of()))
				.isInstanceOf(InvalidOutputDurationException.class)
				.hasMessageContaining("greater than 0");
		assertThatThrownBy(() -> EditorTimelineDurations.assertPositive(0L))
				.isInstanceOf(InvalidOutputDurationException.class);
	}

	@Test
	void twoXShortensOutputAndFitsAudio() {
		List<EditorSegment> visual = List.of(
				EditorSegment.video(UUID.randomUUID().toString(), "A", 10_000, 20_000, null, 2.0d)
		);
		assertThat(visual.getFirst().durationMillis()).isEqualTo(5_000L);
		EditorTimelineDurations.assertFitsLockedAudio(true, 27_000L, visual);
	}
}
