package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.config.RecordingTaskExecutor;
import com.vhmedia.livedownloader.exception.ConcurrentRecordingLimitException;
import com.vhmedia.livedownloader.exception.FfmpegStartException;
import com.vhmedia.livedownloader.util.UrlRedactor;
import com.vhmedia.livedownloader.util.ProcessDestroyOnExit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts and supervises FFmpeg livestream recordings into MPEG-TS files.
 * <p>
 * HTTP threads only schedule work; the FFmpeg process runs on
 * {@link RecordingTaskExecutor}.
 */
@Slf4j
@Service
public class FfmpegRecordingService {

	private static final long DESTROY_GRACE_MILLIS = 2_000L;

	private final MediaProperties mediaProperties;
	private final RecordingProcessRegistry registry;
	private final ApplicationEventPublisher eventPublisher;
	private final RecordingTaskExecutor recordingTaskExecutor;
	private final Semaphore recordingSlots;
	private final ExecutorService ioExecutor;

	public FfmpegRecordingService(
			MediaProperties mediaProperties,
			RecordingProcessRegistry registry,
			ApplicationEventPublisher eventPublisher,
			RecordingTaskExecutor recordingTaskExecutor
	) {
		this.mediaProperties = mediaProperties;
		this.registry = registry;
		this.eventPublisher = eventPublisher;
		this.recordingTaskExecutor = recordingTaskExecutor;
		this.recordingSlots = new Semaphore(mediaProperties.getMaxConcurrentRecordings(), true);
		this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Starts an asynchronous recording. Returns immediately after the job is accepted.
	 */
	public void startRecording(UUID jobId, String streamUrl, Path destination) {
		if (jobId == null) {
			throw new FfmpegStartException("jobId must not be null");
		}
		if (streamUrl == null || streamUrl.isBlank()) {
			throw new FfmpegStartException("streamUrl must not be blank");
		}
		if (destination == null) {
			throw new FfmpegStartException("destination must not be null");
		}
		if (registry.contains(jobId)) {
			throw new FfmpegStartException("Recording already running for job " + jobId);
		}
		boolean acquired = recordingSlots.tryAcquire();
		if (!acquired) {
			throw new ConcurrentRecordingLimitException(
					"Maximum concurrent recordings exceeded (" + mediaProperties.getMaxConcurrentRecordings() + ")"
			);
		}

		try {
			recordingTaskExecutor.execute(() -> runRecording(jobId, streamUrl.trim(), destination));
		} catch (RuntimeException ex) {
			recordingSlots.release();
			throw new FfmpegStartException("Failed to schedule recording task", ex);
		}
	}

	/**
	 * Idempotent graceful stop:
	 * <ol>
	 *   <li>write {@code q} to FFmpeg stdin and flush</li>
	 *   <li>wait {@code stop-timeout-seconds}</li>
	 *   <li>{@link Process#destroy()}</li>
	 *   <li>short grace period</li>
	 *   <li>{@link Process#destroyForcibly()} only as last resort</li>
	 * </ol>
	 *
	 * @return {@code true} if a live process was found and stop was initiated;
	 * {@code false} if the process was already gone (race with natural end)
	 */
	public boolean requestGracefulStop(UUID jobId) {
		Optional<RunningRecording> optional = registry.get(jobId);
		if (optional.isEmpty()) {
			log.info("No active FFmpeg process to stop jobId={}", jobId);
			return false;
		}

		RunningRecording running = optional.get();
		Process process = running.getProcess();

		if (!process.isAlive()) {
			log.info("FFmpeg process already exited jobId={}", jobId);
			running.markStopRequested();
			return false;
		}

		if (!running.markStopRequested()) {
			log.info("Graceful stop already in progress jobId={}", jobId);
			return true;
		}

		log.info("Requesting graceful FFmpeg stop via stdin 'q' jobId={}", jobId);
		sendQuitSignal(process);
		ioExecutor.execute(() -> escalateStop(jobId, process));
		return true;
	}

	public boolean isRunning(UUID jobId) {
		return registry.get(jobId)
				.map(running -> running.getProcess().isAlive())
				.orElse(false);
	}

	public Optional<RunningRecording> getRunning(UUID jobId) {
		return registry.get(jobId);
	}

	private void escalateStop(UUID jobId, Process process) {
		try {
			boolean exited = process.waitFor(mediaProperties.getStopTimeoutSeconds(), TimeUnit.SECONDS);
			if (exited || !process.isAlive()) {
				log.info("FFmpeg exited after quit signal jobId={}", jobId);
				return;
			}

			log.warn(
					"FFmpeg still alive after {}s quit wait; calling destroy() jobId={}",
					mediaProperties.getStopTimeoutSeconds(),
					jobId
			);
			process.destroy();

			boolean exitedAfterDestroy = process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
			if (exitedAfterDestroy || !process.isAlive()) {
				log.info("FFmpeg exited after destroy() jobId={}", jobId);
				return;
			}

			log.warn("FFmpeg still alive after destroy(); calling destroyForcibly() jobId={}", jobId);
			process.destroyForcibly();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private static void sendQuitSignal(Process process) {
		try {
			OutputStream stdin = process.getOutputStream();
			stdin.write('q');
			stdin.write('\n');
			stdin.flush();
		} catch (IOException ex) {
			log.debug("Could not write quit signal to FFmpeg stdin (process may have exited): {}", ex.getMessage());
		}
	}

	private void runRecording(UUID jobId, String streamUrl, Path destination) {
		String redactedUrl = UrlRedactor.redact(streamUrl);
		Process process = null;
		RunningRecording running = null;
		AtomicReference<String> lastStderrLine = new AtomicReference<>();
		AtomicReference<RecordingProgress> lastProgress = new AtomicReference<>();

		try {
			Path parent = destination.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			List<String> command = buildCommand(streamUrl, destination);
			log.info(
					"Starting FFmpeg recording jobId={} url={} output={} browserHeaders={}",
					jobId,
					redactedUrl,
					destination,
					mediaProperties.isHttpBrowserHeadersEnabled()
			);

			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(false);
			process = processBuilder.start();
			ProcessDestroyOnExit.register(process);

			running = new RunningRecording(jobId, process, destination);
			if (!registry.putIfAbsent(jobId, running)) {
				process.destroyForcibly();
				throw new FfmpegStartException("Recording already running for job " + jobId);
			}

			CompletableFuture<Void> progressFuture = readProgressAsync(jobId, running, process.getInputStream(), lastProgress);
			CompletableFuture<Void> stderrFuture = drainStderrAsync(jobId, process.getErrorStream(), lastStderrLine);

			int exitCode = process.waitFor();
			awaitQuietly(progressFuture);
			awaitQuietly(stderrFuture);

			RecordingExitReason reason = resolveExitReason(running, exitCode);
			Long durationMillis = Optional.ofNullable(lastProgress.get())
					.map(RecordingProgress::getOutTimeMs)
					.orElse(null);
			Long downloadedBytes = resolveDownloadedBytes(destination, lastProgress.get());

			String errorMessage = null;
			if (reason == RecordingExitReason.FAILED) {
				errorMessage = buildFailureMessage(exitCode, lastStderrLine.get());
				MediaHttpFailureKind category = MediaHttpFailureClassifier.classify(lastStderrLine.get(), null);
				log.warn(
						"FFmpeg recording failed category={} jobId={} exitCode={} detail={}",
						category,
						jobId,
						exitCode,
						errorMessage
				);
			} else if (reason == RecordingExitReason.COMPLETED_NATURALLY) {
				log.info("FFmpeg recording completed naturally jobId={} exitCode={}", jobId, exitCode);
			} else {
				log.info("FFmpeg recording stopped by user jobId={} exitCode={}", jobId, exitCode);
			}

			publishFinished(RecordingExitResult.builder()
					.jobId(jobId)
					.reason(reason)
					.exitCode(exitCode)
					.outputPath(destination)
					.errorMessage(errorMessage)
					.durationMillis(durationMillis)
					.downloadedBytes(downloadedBytes)
					.build());
		} catch (Exception ex) {
			MediaHttpFailureKind category = ex instanceof IOException ioException
					? MediaHttpFailureClassifier.classifyStartFailure(ioException)
					: MediaHttpFailureClassifier.classify(ex.getMessage(), ex);
			log.error(
					"FFmpeg recording crashed category={} jobId={} url={} detail={}",
					category,
					jobId,
					redactedUrl,
					UrlRedactor.redactInText(ex.getMessage()),
					ex
			);
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			String clientSafeError = category == MediaHttpFailureKind.EXECUTABLE_MISSING
					? "ffmpeg executable is missing or not executable"
					: "Recording failed to start or monitor FFmpeg process";
			publishFinished(RecordingExitResult.builder()
					.jobId(jobId)
					.reason(RecordingExitReason.FAILED)
					.exitCode(-1)
					.outputPath(destination)
					.errorMessage(clientSafeError)
					.build());
		} finally {
			registry.remove(jobId);
			recordingSlots.release();
		}
	}

	/**
	 * Builds ffmpeg argv as discrete ProcessBuilder entries (no shell).
	 * Browser HTTP options are inserted before {@code -i}; the URL is never rewritten.
	 */
	List<String> buildCommand(String streamUrl, Path destination) {
		List<String> command = new ArrayList<>();
		command.add(mediaProperties.getFfmpegPath());
		command.add("-hide_banner");
		command.add("-y");
		MediaHttpRequestArgs.appendBrowserCompatibleHttpArgs(command, mediaProperties);
		command.add("-i");
		command.add(streamUrl);
		command.add("-map");
		command.add("0:v:0?");
		command.add("-map");
		command.add("0:a:0?");
		command.add("-c");
		command.add("copy");
		command.add("-f");
		command.add("mpegts");
		command.add("-progress");
		command.add("pipe:1");
		command.add("-nostats");
		command.add(destination.toAbsolutePath().toString());
		return command;
	}

	private CompletableFuture<Void> readProgressAsync(
			UUID jobId,
			RunningRecording running,
			InputStream stdout,
			AtomicReference<RecordingProgress> lastProgress
	) {
		return CompletableFuture.runAsync(() -> {
			Map<String, String> block = new LinkedHashMap<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					FfmpegProgressParser.acceptLine(block, line).ifPresent(progress -> {
						running.updateProgress(progress);
						lastProgress.set(progress);
						eventPublisher.publishEvent(new RecordingProgressEvent(jobId, progress));
					});
				}
			} catch (IOException ex) {
				log.debug("FFmpeg progress stream closed jobId={}: {}", jobId, ex.getMessage());
			}
		}, ioExecutor);
	}

	private CompletableFuture<Void> drainStderrAsync(
			UUID jobId,
			InputStream stderr,
			AtomicReference<String> lastStderrLine
	) {
		return CompletableFuture.runAsync(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.isBlank()) {
						lastStderrLine.set(line);
						log.debug("ffmpeg[{}] {}", jobId, UrlRedactor.redactInText(line));
					}
				}
			} catch (IOException ex) {
				log.debug("FFmpeg stderr stream closed jobId={}: {}", jobId, ex.getMessage());
			}
		}, ioExecutor);
	}

	private static RecordingExitReason resolveExitReason(RunningRecording running, int exitCode) {
		if (running != null && running.isStopRequested()) {
			return RecordingExitReason.STOPPED_BY_USER;
		}
		if (exitCode == 0) {
			return RecordingExitReason.COMPLETED_NATURALLY;
		}
		return RecordingExitReason.FAILED;
	}

	private static Long resolveDownloadedBytes(Path destination, RecordingProgress progress) {
		if (progress != null && progress.getTotalSize() != null && progress.getTotalSize() >= 0) {
			return progress.getTotalSize();
		}
		try {
			if (Files.exists(destination)) {
				return Files.size(destination);
			}
		} catch (IOException ignored) {
			// fall through
		}
		return null;
	}

	private void publishFinished(RecordingExitResult result) {
		eventPublisher.publishEvent(new RecordingFinishedEvent(result));
	}

	private static String buildFailureMessage(int exitCode, String detail) {
		if (detail == null || detail.isBlank()) {
			return "FFmpeg exited with code " + exitCode;
		}
		String trimmed = UrlRedactor.redactInText(detail.trim());
		if (trimmed.length() > 500) {
			trimmed = trimmed.substring(0, 500) + "...";
		}
		return "FFmpeg exited with code " + exitCode + ": " + trimmed;
	}

	private static void awaitQuietly(CompletableFuture<?> future) {
		try {
			future.get(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// best-effort drain
		}
	}
}
