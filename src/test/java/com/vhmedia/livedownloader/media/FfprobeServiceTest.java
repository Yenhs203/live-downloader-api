package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FfprobeServiceTest {

	@Mock
	private StreamProbeParser streamProbeParser;

	private MediaProperties properties;
	private FfprobeService ffprobeService;

	@BeforeEach
	void setUp() {
		properties = new MediaProperties();
		properties.setFfprobePath("ffprobe");
		properties.setFfmpegPath("ffmpeg");
		properties.setRecordingsDirectory("./recordings-test");
		properties.setHttpBrowserHeadersEnabled(true);
		properties.setHttpUserAgent(
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139 Safari/537.36"
		);
		properties.setHttpReferer("https://www.tiktok.com/");
		ffprobeService = new FfprobeService(properties, streamProbeParser);
	}

	@Test
	void buildCommandAddsBrowserHeadersBeforeInputUrl() {
		String url = "https://cdn.example.com/live.flv?sign=abc&expire=123";

		List<String> command = ffprobeService.buildCommand(url);

		assertThat(command).containsExactly(
				"ffprobe",
				"-v",
				"error",
				"-show_streams",
				"-show_format",
				"-of",
				"json",
				"-user_agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139 Safari/537.36",
				"-headers",
				"Referer: https://www.tiktok.com/\r\n",
				url
		);
		assertThat(command.get(command.size() - 1)).isSameAs(url);
	}

	@Test
	void buildCommandOmitsHeadersWhenDisabled() {
		properties.setHttpBrowserHeadersEnabled(false);
		String url = "https://cdn.example.com/live.flv?token=secret";

		assertThat(ffprobeService.buildCommand(url)).containsExactly(
				"ffprobe",
				"-v",
				"error",
				"-show_streams",
				"-show_format",
				"-of",
				"json",
				url
		);
	}

	@Test
	void buildCommandPreservesQueryStringExactly() {
		String url = "https://v16-webapp-prime.tiktok.com/video/tos/x.flv?a=1988&sign=%2Fabc%3D&expire=999";

		List<String> command = ffprobeService.buildCommand(url);

		assertThat(command.getLast()).isEqualTo(url);
		assertThat(command.getLast()).contains("sign=%2Fabc%3D");
	}

	@Test
	void buildLocalFileCommandOmitsHttpHeaders() {
		java.nio.file.Path file = java.nio.file.Path.of("C:/tmp/source.mp4");
		List<String> command = ffprobeService.buildLocalFileCommand(file);
		assertThat(command).startsWith("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json");
		assertThat(command).doesNotContain("-user_agent");
		assertThat(command).doesNotContain("-headers");
		assertThat(command.getLast()).isEqualTo(file.toAbsolutePath().toString());
	}
}
