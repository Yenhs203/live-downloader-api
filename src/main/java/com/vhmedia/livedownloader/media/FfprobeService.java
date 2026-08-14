package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.exception.MediaExecutableMissingException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.util.UrlRedactor;
import com.vhmedia.livedownloader.util.ProcessDestroyOnExit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class FfprobeService {

	private final MediaProperties mediaProperties;
	private final StreamProbeParser streamProbeParser;
	private final ExecutorService ioExecutor;

	public FfprobeService(MediaProperties mediaProperties, StreamProbeParser streamProbeParser) {
		this.mediaProperties = mediaProperties;
		this.streamProbeParser = streamProbeParser;
		this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Probes a livestream URL with ffprobe and returns detected stream metadata.
	 */
	public StreamProbeResult probe(String streamUrl) {
		if (streamUrl == null || streamUrl.isBlank()) {
			throw new StreamProbeException("Stream URL must not be blank");
		}

		String redactedUrl = UrlRedactor.redact(streamUrl);
		List<String> command = buildCommand(streamUrl);

		log.info(
				"Probing stream with ffprobe url={} browserHeaders={}",
				redactedUrl,
				mediaProperties.isHttpBrowserHeadersEnabled()
		);

		Process process = null;
		try {
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(false);
			process = processBuilder.start();
			ProcessDestroyOnExit.register(process);

			CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
			CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

			boolean finished = process.waitFor(mediaProperties.getProbeTimeoutSeconds(), TimeUnit.SECONDS);
			if (!finished) {
				destroyProcess(process);
				String stderr = awaitQuietly(stderrFuture);
				awaitQuietly(stdoutFuture);
				String sanitizedDetail = truncate(UrlRedactor.redactInText(firstNonBlank(stderr, null)), 500);
				log.warn(
						"ffprobe timed out category={} after {}s url={} detail={}",
						MediaHttpFailureKind.TIMEOUT,
						mediaProperties.getProbeTimeoutSeconds(),
						redactedUrl,
						sanitizedDetail
				);
				throw new StreamProbeTimeoutException(
						"ffprobe timed out after " + mediaProperties.getProbeTimeoutSeconds() + " seconds"
				);
			}

			int exitCode = process.exitValue();
			String stdout = joinOutput(stdoutFuture);
			String stderr = joinOutput(stderrFuture);

			if (exitCode != 0) {
				String detail = firstNonBlank(stderr, stdout);
				String sanitizedDetail = truncate(UrlRedactor.redactInText(detail), 500);
				MediaHttpFailureKind category = MediaHttpFailureClassifier.classify(detail, null);
				log.warn(
						"ffprobe failed category={} exitCode={} url={} detail={}",
						category,
						exitCode,
						redactedUrl,
						sanitizedDetail
				);
				throw new StreamProbeException(buildFailureMessage(exitCode, detail));
			}

			StreamProbeResult result = streamProbeParser.parse(stdout);
			log.info(
					"ffprobe success url={} format={} video={} audio={} {}x{} fps={}",
					redactedUrl,
					result.getFormatName(),
					result.getVideoCodec(),
					result.getAudioCodec(),
					result.getWidth(),
					result.getHeight(),
					result.getFps()
			);
			return result;
		} catch (StreamProbeException ex) {
			throw ex;
		} catch (IOException ex) {
			MediaHttpFailureKind category = MediaHttpFailureClassifier.classifyStartFailure(ex);
			log.error(
					"Failed to start ffprobe category={} url={} detail={}",
					category,
					redactedUrl,
					truncate(UrlRedactor.redactInText(ex.getMessage()), 500),
					ex
			);
			if (category == MediaHttpFailureKind.EXECUTABLE_MISSING) {
				throw new MediaExecutableMissingException("ffprobe executable is missing or not executable", ex);
			}
			throw new StreamProbeException("Failed to start ffprobe process", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			destroyProcess(process);
			throw new StreamProbeException("ffprobe was interrupted", ex);
		}
	}

	/**
	 * Builds ffprobe argv as discrete ProcessBuilder entries (no shell).
	 * HTTP options are inserted before the input URL; the URL is never rewritten.
	 */
	List<String> buildCommand(String streamUrl) {
		List<String> command = new ArrayList<>();
		command.add(mediaProperties.getFfprobePath());
		command.add("-v");
		command.add("error");
		command.add("-show_streams");
		command.add("-show_format");
		command.add("-of");
		command.add("json");
		MediaHttpRequestArgs.appendBrowserCompatibleHttpArgs(command, mediaProperties);
		command.add(streamUrl);
		return command;
	}

	private CompletableFuture<String> readStreamAsync(InputStream inputStream) {
		return CompletableFuture.supplyAsync(() -> readFully(inputStream), ioExecutor);
	}

	private static String readFully(InputStream inputStream) {
		try {
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new StreamProbeException("Failed to read ffprobe process output", ex);
		}
	}

	private static void destroyProcess(Process process) {
		if (process == null) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private static String joinOutput(CompletableFuture<String> future) {
		try {
			return future.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof StreamProbeException streamProbeException) {
				throw streamProbeException;
			}
			throw new StreamProbeException("Failed to read ffprobe process output", cause);
		}
	}

	private static String awaitQuietly(CompletableFuture<String> future) {
		try {
			return future.get(2, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// best-effort drain after kill
			return null;
		}
	}

	private static String buildFailureMessage(int exitCode, String detail) {
		if (detail == null || detail.isBlank()) {
			return "ffprobe failed with exit code " + exitCode;
		}
		return "ffprobe failed with exit code " + exitCode + ": "
				+ truncate(UrlRedactor.redactInText(detail.trim()), 500);
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		if (second != null && !second.isBlank()) {
			return second;
		}
		return null;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength) + "...";
	}
}
