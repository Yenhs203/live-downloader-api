package com.vhmedia.livedownloader;

import com.vhmedia.livedownloader.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(LiveDownloaderApplicationTests.TestConfig.class)
@TestPropertySource(properties = {
		"app.media.ffmpeg-path=ffmpeg",
		"app.media.ffprobe-path=ffprobe",
		"app.media.recordings-directory=./recordings-test",
		"app.media.max-concurrent-recordings=1",
		"app.media.probe-timeout-seconds=10",
		"app.media.stop-timeout-seconds=10"
})
class LiveDownloaderApplicationTests {

	@Autowired
	private MediaProperties mediaProperties;

	@Test
	void bindsMediaProperties() {
		assertThat(mediaProperties.getFfmpegPath()).isEqualTo("ffmpeg");
		assertThat(mediaProperties.getFfprobePath()).isEqualTo("ffprobe");
		assertThat(mediaProperties.getRecordingsDirectory()).isEqualTo("./recordings-test");
		assertThat(mediaProperties.getMaxConcurrentRecordings()).isEqualTo(1);
		assertThat(mediaProperties.getProbeTimeoutSeconds()).isEqualTo(10);
		assertThat(mediaProperties.getStopTimeoutSeconds()).isEqualTo(10);
		assertThat(mediaProperties.isDeleteTempAfterRemux()).isTrue();
	}

	@EnableConfigurationProperties(MediaProperties.class)
	static class TestConfig {
	}
}
