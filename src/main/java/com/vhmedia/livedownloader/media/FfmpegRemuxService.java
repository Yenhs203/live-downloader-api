package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.exception.RemuxException;
import com.vhmedia.livedownloader.util.UrlRedactor;
import com.vhmedia.livedownloader.util.ProcessDestroyOnExit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Remuxes MPEG-TS to MP4 without re-encoding.
 * <pre>
 * ffmpeg -hide_banner -y -i input.ts -map 0:v:0? -map 0:a:0? -c copy -movflags +faststart output.mp4
 * </pre>
 */
@Slf4j
@Service
public class FfmpegRemuxService {

	private static final int REMUX_TIMEOUT_MINUTES = 30;

	private final MediaProperties mediaProperties;
	private final ExecutorService ioExecutor;

	public FfmpegRemuxService(MediaProperties mediaProperties) {
		this.mediaProperties = mediaProperties;
		this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Remuxes {@code inputTs} to {@code outputMp4} using stream copy.
	 *
	 * @return size in bytes of the created MP4
	 */
	public long remux(Path inputTs, Path outputMp4) {
		if (inputTs == null || outputMp4 == null) {
			throw new RemuxException("Remux input/output paths must not be null");
		}
		if (!Files.exists(inputTs)) {
			throw new RemuxException("Temp TS file does not exist: " + inputTs.getFileName());
		}

		try {
			long size = Files.size(inputTs);
			if (size <= 0) {
				throw new RemuxException("Temp TS file is empty: " + inputTs.getFileName());
			}
		} catch (IOException ex) {
			throw new RemuxException("Unable to read temp TS file", ex);
		}

		List<String> command = buildCommand(inputTs, outputMp4);
		log.info("Starting remux input={} output={}", inputTs.getFileName(), outputMp4.getFileName());

		Process process = null;
		try {
			Path parent = outputMp4.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(false);
			process = processBuilder.start();
			ProcessDestroyOnExit.register(process);

			CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
			CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

			boolean finished = process.waitFor(REMUX_TIMEOUT_MINUTES, TimeUnit.MINUTES);
			if (!finished) {
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				throw new RemuxException("Remux timed out");
			}

			int exitCode = process.exitValue();
			String stderr = joinOutput(stderrFuture);
			joinOutput(stdoutFuture);

			if (exitCode != 0) {
				throw new RemuxException(buildFailureMessage(exitCode, stderr));
			}

			if (!Files.exists(outputMp4)) {
				throw new RemuxException("Remux completed but MP4 output is missing");
			}

			long mp4Size = Files.size(outputMp4);
			if (mp4Size <= 0) {
				throw new RemuxException("Remux completed but MP4 output is empty");
			}

			log.info("Remux completed output={} sizeBytes={}", outputMp4.getFileName(), mp4Size);
			return mp4Size;
		} catch (RemuxException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new RemuxException("Failed to start remux process", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			throw new RemuxException("Remux interrupted", ex);
		}
	}

	List<String> buildCommand(Path inputTs, Path outputMp4) {
		List<String> command = new ArrayList<>();
		command.add(mediaProperties.getFfmpegPath());
		command.add("-hide_banner");
		command.add("-y");
		command.add("-i");
		command.add(inputTs.toAbsolutePath().toString());
		command.add("-map");
		command.add("0:v:0?");
		command.add("-map");
		command.add("0:a:0?");
		command.add("-c");
		command.add("copy");
		command.add("-movflags");
		command.add("+faststart");
		command.add(outputMp4.toAbsolutePath().toString());
		return command;
	}

	private CompletableFuture<String> readAsync(InputStream inputStream) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException ex) {
				throw new RemuxException("Failed to read remux process output", ex);
			}
		}, ioExecutor);
	}

	private static String joinOutput(CompletableFuture<String> future) {
		try {
			return future.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof RemuxException remuxException) {
				throw remuxException;
			}
			throw new RemuxException("Failed to read remux process output", cause);
		}
	}

	private static String buildFailureMessage(int exitCode, String detail) {
		if (detail == null || detail.isBlank()) {
			return "Remux failed with exit code " + exitCode;
		}
		String trimmed = UrlRedactor.redactInText(detail.trim());
		if (trimmed.length() > 500) {
			trimmed = trimmed.substring(0, 500) + "...";
		}
		return "Remux failed with exit code " + exitCode + ": " + trimmed;
	}
}
