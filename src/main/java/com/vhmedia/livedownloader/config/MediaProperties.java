package com.vhmedia.livedownloader.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties {

	@NotBlank
	private String ffmpegPath;

	@NotBlank
	private String ffprobePath;

	@NotBlank
	private String recordingsDirectory;

	@Min(1)
	private int maxConcurrentRecordings = 3;

	@Min(1)
	private int probeTimeoutSeconds = 30;

	@Min(1)
	private int stopTimeoutSeconds = 15;

	/**
	 * When true, delete the intermediate {@code .ts} file after a successful remux.
	 * On remux failure the {@code .ts} is always retained for recovery.
	 */
	private boolean deleteTempAfterRemux = true;

	/**
	 * How often recording progress is written to the database (SSE updates are not throttled).
	 */
	@Min(1)
	private int progressPersistIntervalSeconds = 3;

	/**
	 * SSE connection idle timeout. Clients should reconnect if needed.
	 */
	@Min(30)
	private int sseTimeoutSeconds = 1800;

	/**
	 * Minimum free bytes required on the recordings volume at startup (and before accepting new work via health).
	 */
	@Min(0)
	private long minFreeDiskBytes = 536_870_912L;

	/**
	 * When true, fail application startup if ffmpeg/ffprobe cannot be executed.
	 */
	private boolean validateExecutablesOnStartup = false;

	/**
	 * When true, ffmpeg/ffprobe HTTP(S) requests send browser-compatible {@code -user_agent}
	 * and {@code -headers} (Referer) so CDN endpoints that reject bare clients can be probed/recorded.
	 */
	private boolean httpBrowserHeadersEnabled = true;

	/**
	 * Value for ffmpeg/ffprobe {@code -user_agent}.
	 */
	@NotBlank
	private String httpUserAgent =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139 Safari/537.36";

	/**
	 * Referer origin sent via ffmpeg/ffprobe {@code -headers} (terminated with {@code \\r\\n} at runtime).
	 */
	@NotBlank
	private String httpReferer = "https://www.tiktok.com/";
}
