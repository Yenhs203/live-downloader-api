package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.config.RecordingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FfmpegRecordingServiceTest {

	@Mock
	private RecordingProcessRegistry registry;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private RecordingTaskExecutor recordingTaskExecutor;

	private MediaProperties properties;
	private FfmpegRecordingService recordingService;

	@BeforeEach
	void setUp() {
		properties = new MediaProperties();
		properties.setFfmpegPath("ffmpeg");
		properties.setFfprobePath("ffprobe");
		properties.setRecordingsDirectory("./recordings-test");
		properties.setMaxConcurrentRecordings(2);
		properties.setHttpBrowserHeadersEnabled(true);
		properties.setHttpUserAgent(
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139 Safari/537.36"
		);
		properties.setHttpReferer("https://www.tiktok.com/");
		recordingService = new FfmpegRecordingService(properties, registry, eventPublisher, recordingTaskExecutor);
	}

	@Test
	void buildCommandAddsBrowserHeadersBeforeInput() {
		String url = "https://cdn.example.com/live.flv?sign=abc&expire=123";
		Path destination = Path.of("recordings-test", "job.ts").toAbsolutePath();

		List<String> command = recordingService.buildCommand(url, destination);

		assertThat(command).containsExactly(
				"ffmpeg",
				"-hide_banner",
				"-y",
				"-user_agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139 Safari/537.36",
				"-headers",
				"Referer: https://www.tiktok.com/\r\n",
				"-i",
				url,
				"-map",
				"0:v:0?",
				"-map",
				"0:a:0?",
				"-c",
				"copy",
				"-f",
				"mpegts",
				"-progress",
				"pipe:1",
				"-nostats",
				destination.toString()
		);
		assertThat(command.get(command.indexOf("-i") + 1)).isSameAs(url);
	}

	@Test
	void buildCommandOmitsHeadersWhenDisabled() {
		properties.setHttpBrowserHeadersEnabled(false);
		String url = "https://cdn.example.com/live.flv?token=secret";
		Path destination = Path.of("recordings-test", "job.ts").toAbsolutePath();

		List<String> command = recordingService.buildCommand(url, destination);

		assertThat(command).doesNotContain("-user_agent", "-headers");
		assertThat(command.get(command.indexOf("-i") + 1)).isEqualTo(url);
	}
}
