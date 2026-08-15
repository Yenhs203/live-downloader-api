package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StreamProbeParserTest {

	private StreamProbeParser parser;

	@BeforeEach
	void setUp() {
		parser = new StreamProbeParser(JsonMapper.builder().build());
	}

	@Test
	void parsesVideoAndAudioStreamMetadata() {
		String json = """
				{
				  "streams": [
				    {
				      "index": 0,
				      "codec_name": "h264",
				      "codec_type": "video",
				      "width": 1920,
				      "height": 1080,
				      "avg_frame_rate": "30/1",
				      "r_frame_rate": "30/1"
				    },
				    {
				      "index": 1,
				      "codec_name": "aac",
				      "codec_type": "audio",
				      "sample_rate": "44100",
				      "channels": 2
				    }
				  ],
				  "format": {
				    "format_name": "flv",
				    "format_long_name": "FLV (Flash Video)"
				  }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.isHasVideo()).isTrue();
		assertThat(result.isHasAudio()).isTrue();
		assertThat(result.getFormatName()).isEqualTo("flv");
		assertThat(result.getVideoCodec()).isEqualTo("h264");
		assertThat(result.getWidth()).isEqualTo(1920);
		assertThat(result.getHeight()).isEqualTo(1080);
		assertThat(result.getFps()).isCloseTo(30.0d, within(0.0001d));
		assertThat(result.getAudioCodec()).isEqualTo("aac");
		assertThat(result.getAudioSampleRate()).isEqualTo(44100);
		assertThat(result.getAudioChannels()).isEqualTo(2);
		assertThat(result.getDurationMillis()).isNull();
	}

	@Test
	void usesRFrameRateWhenAvgFrameRateMissingOrInvalid() {
		String json = """
				{
				  "streams": [
				    {
				      "codec_name": "h264",
				      "codec_type": "video",
				      "width": 1280,
				      "height": 720,
				      "avg_frame_rate": "0/0",
				      "r_frame_rate": "30000/1001"
				    }
				  ],
				  "format": { "format_name": "hls" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.isHasVideo()).isTrue();
		assertThat(result.isHasAudio()).isFalse();
		assertThat(result.getFps()).isCloseTo(30000.0d / 1001.0d, within(0.0001d));
		assertThat(result.getFormatName()).isEqualTo("hls");
	}

	@Test
	void parsesAudioOnlyStream() {
		String json = """
				{
				  "streams": [
				    {
				      "codec_name": "mp3",
				      "codec_type": "audio",
				      "sample_rate": "48000",
				      "channels": 1
				    }
				  ],
				  "format": { "format_name": "mp3" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.isHasVideo()).isFalse();
		assertThat(result.isHasAudio()).isTrue();
		assertThat(result.getVideoCodec()).isNull();
		assertThat(result.getAudioCodec()).isEqualTo("mp3");
		assertThat(result.getAudioSampleRate()).isEqualTo(48000);
		assertThat(result.getAudioChannels()).isEqualTo(1);
	}

	@Test
	void usesFirstVideoAndFirstAudioStreamOnly() {
		String json = """
				{
				  "streams": [
				    {
				      "codec_name": "h264",
				      "codec_type": "video",
				      "width": 1920,
				      "height": 1080,
				      "avg_frame_rate": "25/1"
				    },
				    {
				      "codec_name": "hevc",
				      "codec_type": "video",
				      "width": 640,
				      "height": 360,
				      "avg_frame_rate": "15/1"
				    },
				    {
				      "codec_name": "aac",
				      "codec_type": "audio",
				      "sample_rate": "44100",
				      "channels": 2
				    },
				    {
				      "codec_name": "opus",
				      "codec_type": "audio",
				      "sample_rate": "48000",
				      "channels": 2
				    }
				  ],
				  "format": { "format_name": "mpegts" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.getVideoCodec()).isEqualTo("h264");
		assertThat(result.getWidth()).isEqualTo(1920);
		assertThat(result.getHeight()).isEqualTo(1080);
		assertThat(result.getFps()).isCloseTo(25.0d, within(0.0001d));
		assertThat(result.getAudioCodec()).isEqualTo("aac");
		assertThat(result.getAudioSampleRate()).isEqualTo(44100);
	}

	@Test
	void handlesEmptyStreamsArray() {
		String json = """
				{
				  "streams": [],
				  "format": { "format_name": "unknown" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.isHasVideo()).isFalse();
		assertThat(result.isHasAudio()).isFalse();
		assertThat(result.getFormatName()).isEqualTo("unknown");
		assertThat(result.getVideoCodec()).isNull();
		assertThat(result.getAudioCodec()).isNull();
	}

	@Test
	void rejectsBlankJson() {
		assertThatThrownBy(() -> parser.parse("   "))
				.isInstanceOf(StreamProbeException.class)
				.hasMessageContaining("empty");
	}

	@Test
	void rejectsInvalidJson() {
		assertThatThrownBy(() -> parser.parse("{not-json"))
				.isInstanceOf(StreamProbeException.class)
				.hasMessageContaining("parse");
	}

	@Test
	void parseFrameRateHandlesInvalidValues() {
		assertThat(StreamProbeParser.parseFrameRate(null)).isNull();
		assertThat(StreamProbeParser.parseFrameRate("")).isNull();
		assertThat(StreamProbeParser.parseFrameRate("0/0")).isNull();
		assertThat(StreamProbeParser.parseFrameRate("N/A")).isNull();
		assertThat(StreamProbeParser.parseFrameRate("abc/def")).isNull();
		assertThat(StreamProbeParser.parseFrameRate("29.97")).isCloseTo(29.97d, within(0.0001d));
	}

	@Test
	void parsesFormatDurationIntoMillis() {
		String json = """
				{
				  "streams": [
				    {
				      "codec_name": "h264",
				      "codec_type": "video",
				      "width": 640,
				      "height": 360,
				      "avg_frame_rate": "30/1"
				    }
				  ],
				  "format": { "format_name": "mp4", "duration": "4.250000" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.getDurationMillis()).isEqualTo(4250L);
		assertThat(StreamProbeParser.parseDurationMillis(null)).isNull();
		assertThat(StreamProbeParser.parseDurationMillis("N/A")).isNull();
		assertThat(StreamProbeParser.parseDurationMillis("abc")).isNull();
		assertThat(StreamProbeParser.parseDurationMillis("00:00:25.000000")).isEqualTo(25_000L);
	}

	@Test
	void parsesVideoAndAudioStreamDurations() {
		String json = """
				{
				  "streams": [
				    {
				      "codec_name": "h264",
				      "codec_type": "video",
				      "width": 640,
				      "height": 360,
				      "avg_frame_rate": "25/1",
				      "duration": "25.000000"
				    },
				    {
				      "codec_name": "aac",
				      "codec_type": "audio",
				      "sample_rate": "44100",
				      "channels": 2,
				      "tags": { "DURATION": "00:00:25.012000" }
				    }
				  ],
				  "format": { "format_name": "mp4", "duration": "25.000000" }
				}
				""";

		StreamProbeResult result = parser.parse(json);

		assertThat(result.getDurationMillis()).isEqualTo(25_000L);
		assertThat(result.getVideoDurationMillis()).isEqualTo(25_000L);
		assertThat(result.getAudioDurationMillis()).isEqualTo(25_012L);
	}
}
