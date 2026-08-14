package com.vhmedia.livedownloader.media;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegProgressParserTest {

	@Test
	void parsesProgressBlockWhenProgressLineArrives() {
		Map<String, String> block = new LinkedHashMap<>();

		assertThat(FfmpegProgressParser.acceptLine(block, "fps=29.97")).isEmpty();
		assertThat(FfmpegProgressParser.acceptLine(block, "bitrate=2500.5kbits/s")).isEmpty();
		assertThat(FfmpegProgressParser.acceptLine(block, "total_size=123456")).isEmpty();
		assertThat(FfmpegProgressParser.acceptLine(block, "out_time_ms=4500")).isEmpty();
		assertThat(FfmpegProgressParser.acceptLine(block, "speed=1.01x")).isEmpty();

		Optional<RecordingProgress> progress = FfmpegProgressParser.acceptLine(block, "progress=continue");

		assertThat(progress).isPresent();
		assertThat(progress.get().getFps()).isEqualTo(29.97d);
		assertThat(progress.get().getBitrate()).isEqualTo("2500.5kbits/s");
		assertThat(progress.get().getTotalSize()).isEqualTo(123456L);
		assertThat(progress.get().getOutTimeMs()).isEqualTo(4500L);
		assertThat(progress.get().getSpeed()).isEqualTo("1.01x");
		assertThat(progress.get().getProgress()).isEqualTo("continue");
		assertThat(progress.get().isEnd()).isFalse();
		assertThat(block).isEmpty();
	}

	@Test
	void prefersOutTimeUsWhenPresent() {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put("out_time_ms", "999999");
		properties.put("out_time_us", "4500000");
		properties.put("progress", "end");

		RecordingProgress progress = FfmpegProgressParser.parseBlock(properties);

		assertThat(progress.getOutTimeMs()).isEqualTo(4500L);
		assertThat(progress.isEnd()).isTrue();
	}

	@Test
	void ignoresNaAndMalformedValues() {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put("fps", "N/A");
		properties.put("total_size", "abc");
		properties.put("bitrate", "N/A");
		properties.put("progress", "continue");

		RecordingProgress progress = FfmpegProgressParser.parseBlock(properties);

		assertThat(progress.getFps()).isNull();
		assertThat(progress.getTotalSize()).isNull();
		assertThat(progress.getBitrate()).isNull();
		assertThat(progress.getProgress()).isEqualTo("continue");
	}

	@Test
	void ignoresLinesWithoutSeparator() {
		Map<String, String> block = new LinkedHashMap<>();
		assertThat(FfmpegProgressParser.acceptLine(block, "not-a-property")).isEmpty();
		assertThat(block).isEmpty();
	}
}
