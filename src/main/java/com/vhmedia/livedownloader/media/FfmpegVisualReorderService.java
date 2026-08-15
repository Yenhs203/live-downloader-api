package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.editor.EditorAudioCodecPolicy;
import com.vhmedia.livedownloader.editor.EditorAudioExport;
import com.vhmedia.livedownloader.editor.EditorEncodeProfile;
import com.vhmedia.livedownloader.editor.EditorExportCodec;
import com.vhmedia.livedownloader.editor.EditorExportPlan;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.VisualReorderFilterGraph;
import com.vhmedia.livedownloader.exception.ConcurrentEditorLimitException;
import com.vhmedia.livedownloader.exception.EditorRenderCancelledException;
import com.vhmedia.livedownloader.exception.EditorRenderException;
import com.vhmedia.livedownloader.util.ProcessDestroyOnExit;
import com.vhmedia.livedownloader.util.UrlRedactor;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Reorders visual segments with FFmpeg while mapping the original audio timeline unchanged.
 */
@Slf4j
@Service
public class FfmpegVisualReorderService {

	private static final long DESTROY_GRACE_MILLIS = 2_000L;

	private final MediaProperties mediaProperties;
	private final EditorProperties editorProperties;
	private final ApplicationEventPublisher eventPublisher;
	private final Semaphore renderSlots;
	private final ExecutorService ioExecutor;
	private final ConcurrentHashMap<UUID, RunningEditorRender> running = new ConcurrentHashMap<>();

	public FfmpegVisualReorderService(
			MediaProperties mediaProperties,
			EditorProperties editorProperties,
			ApplicationEventPublisher eventPublisher
	) {
		this.mediaProperties = mediaProperties;
		this.editorProperties = editorProperties;
		this.eventPublisher = eventPublisher;
		this.renderSlots = new Semaphore(editorProperties.getMaxConcurrentExports(), true);
		this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Reserve a CPU slot before scheduling FFmpeg. Throws HTTP 429 when all slots are taken.
	 */
	public void acquireExportSlot() {
		if (!renderSlots.tryAcquire()) {
			throw new ConcurrentEditorLimitException(
					"Maximum concurrent editor exports exceeded (" + editorProperties.getMaxConcurrentExports() + ")"
			);
		}
	}

	public void releaseExportSlot() {
		renderSlots.release();
	}

	public boolean isRunning(UUID projectId) {
		RunningEditorRender handle = running.get(projectId);
		return handle != null && handle.getProcess().isAlive();
	}

	public boolean isCancelRequested(UUID projectId) {
		RunningEditorRender handle = running.get(projectId);
		return handle != null && handle.isCancelRequested();
	}

	public boolean requestCancel(UUID projectId) {
		return requestCancel(projectId, null);
	}

	/**
	 * Idempotent graceful cancel: stdin {@code q}, wait, destroy, destroyForcibly.
	 * Only the editor FFmpeg process for this project is signalled — recording is untouched.
	 *
	 * @return {@code true} if a live process was found and cancel was initiated
	 */
	public boolean requestCancel(UUID projectId, UUID exportJobId) {
		RunningEditorRender handle = running.get(projectId);
		if (handle == null) {
			log.info("No active editor render to cancel projectId={} exportJobId={}", projectId, exportJobId);
			return false;
		}
		if (exportJobId != null && handle.getExportJobId() != null && !exportJobId.equals(handle.getExportJobId())) {
			log.info(
					"Skip editor cancel; running exportJobId={} does not match requested exportJobId={} projectId={}",
					handle.getExportJobId(),
					exportJobId,
					projectId
			);
			return false;
		}
		Process process = handle.getProcess();
		if (!process.isAlive()) {
			handle.markCancelRequested();
			log.info("Editor FFmpeg already exited projectId={}", projectId);
			return false;
		}
		if (!handle.markCancelRequested()) {
			log.info("Editor cancel already in progress projectId={}", projectId);
			return true;
		}
		log.info("Requesting graceful editor FFmpeg stop via stdin 'q' projectId={} exportJobId={}", projectId, exportJobId);
		sendQuitSignal(process);
		ioExecutor.execute(() -> escalateStop(projectId, process));
		return true;
	}

	public long render(
			UUID projectId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			boolean hasAudio,
			String audioCodec
	) {
		return render(projectId, sourceMp4, outputMp4, visualOrder, durationMillis, hasAudio, audioCodec, null);
	}

	public long render(
			UUID projectId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan
	) {
		return render(projectId, null, sourceMp4, outputMp4, visualOrder, durationMillis, hasAudio, audioCodec, plan, Map.of());
	}

	public long render(
			UUID projectId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			Map<UUID, Path> imageAssets
	) {
		return render(
				projectId,
				null,
				sourceMp4,
				outputMp4,
				visualOrder,
				durationMillis,
				hasAudio,
				audioCodec,
				plan,
				imageAssets
		);
	}

	/**
	 * Blocks until FFmpeg finishes. Caller must schedule this on {@code EditorTaskExecutor}.
	 * When {@code slotAlreadyHeld} is true the caller reserved the export slot (and must release it).
	 */
	public long render(
			UUID projectId,
			UUID exportJobId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			Map<UUID, Path> imageAssets
	) {
		return render(
				projectId,
				exportJobId,
				sourceMp4,
				outputMp4,
				visualOrder,
				durationMillis,
				durationMillis,
				hasAudio,
				audioCodec,
				plan,
				imageAssets,
				false
		);
	}

	public long renderWithAcquiredSlot(
			UUID projectId,
			UUID exportJobId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			Map<UUID, Path> imageAssets
	) {
		return renderWithAcquiredSlot(
				projectId,
				exportJobId,
				sourceMp4,
				outputMp4,
				visualOrder,
				durationMillis,
				durationMillis,
				hasAudio,
				audioCodec,
				plan,
				imageAssets
		);
	}

	public long renderWithAcquiredSlot(
			UUID projectId,
			UUID exportJobId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long outputDurationMillis,
			long sourceDurationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			Map<UUID, Path> imageAssets
	) {
		return render(
				projectId,
				exportJobId,
				sourceMp4,
				outputMp4,
				visualOrder,
				outputDurationMillis,
				sourceDurationMillis,
				hasAudio,
				audioCodec,
				plan,
				imageAssets,
				true
		);
	}

	private long render(
			UUID projectId,
			UUID exportJobId,
			Path sourceMp4,
			Path outputMp4,
			List<EditorSegment> visualOrder,
			long durationMillis,
			long sourceDurationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			Map<UUID, Path> imageAssets,
			boolean slotAlreadyHeld
	) {
		if (projectId == null || sourceMp4 == null || outputMp4 == null) {
			throw new EditorRenderException("Render paths must not be null");
		}
		if (!Files.isRegularFile(sourceMp4)) {
			throw new EditorRenderException("Editor source file is missing");
		}
		if (visualOrder == null || visualOrder.isEmpty()) {
			throw new EditorRenderException("Visual segments are required");
		}
		if (imageAssets != null) {
			for (Map.Entry<UUID, Path> entry : imageAssets.entrySet()) {
				Path image = entry.getValue();
				if (image == null || !Files.isRegularFile(image)) {
					throw new EditorRenderException("IMAGE asset file is missing: " + entry.getKey());
				}
			}
		}

		boolean acquiredHere = false;
		if (!slotAlreadyHeld) {
			acquireExportSlot();
			acquiredHere = true;
		}

		Process process = null;
		try {
			long visualMillis = VisualReorderFilterGraph.visualDurationMillis(visualOrder);
			// Pad only absorbs timebase slack vs requested output duration — never extend back to source length.
			long padMillis = Math.max(0L, durationMillis - visualMillis);
			VisualReorderFilterGraph.CompiledVisualGraph compiled = VisualReorderFilterGraph.compile(
					visualOrder,
					imageAssets,
					padMillis,
					plan
			);
			String filter = compiled.filterComplex();
			if (VisualReorderFilterGraph.visualGraphTouchesAudio(filter)
					|| VisualReorderFilterGraph.containsForbiddenAudioRetiming(filter)) {
				throw new EditorRenderException("Internal error: visual filter graph must not re-time or concat audio");
			}

			List<String> command = buildCommand(
					sourceMp4,
					outputMp4,
					filter,
					durationMillis,
					sourceDurationMillis,
					hasAudio,
					audioCodec,
					plan,
					compiled.imageInputs()
			);
			boolean audioFiltered = EditorAudioExport.needsFilterTrim(
					hasAudio,
					durationMillis,
					sourceDurationMillis,
					editorProperties.getCoverageEpsilonMillis()
			);
			log.info(
					"Starting visual reorder projectId={} exportJobId={} durationMs={} visual={} hasAudio={} audioFiltered={} audioMode={} padMs={} exportFps={} exportResolution={} outputWidth={} outputHeight={} outputFps={} keepOriginalAudio=true",
					projectId,
					exportJobId,
					durationMillis,
					VisualReorderFilterGraph.describe(visualOrder),
					hasAudio,
					audioFiltered,
					hasAudio ? (audioFiltered ? "AAC" : EditorAudioCodecPolicy.forMp4(audioCodec)) : "none",
					padMillis,
					plan != null ? plan.settings().fps().apiValue() : "ORIGINAL",
					plan != null ? plan.settings().resolution().apiValue() : "ORIGINAL",
					plan != null ? plan.outputWidth() : null,
					plan != null ? plan.outputHeight() : null,
					plan != null ? plan.outputFps() : null
			);

			Path parent = outputMp4.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.deleteIfExists(outputMp4);

			// Argv list only — never `cmd /c` / `sh -c`. Paths and the filter graph are discrete entries.
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(false);
			process = processBuilder.start();
			ProcessDestroyOnExit.register(process);
			RunningEditorRender handle = new RunningEditorRender(projectId, exportJobId, process);
			running.put(projectId, handle);

			CompletableFuture<Void> progressFuture = readProgressAsync(
					projectId,
					exportJobId,
					process.getInputStream(),
					durationMillis
			);
			CompletableFuture<String> stderrFuture = readFullyAsync(process.getErrorStream());

			boolean finished = process.waitFor(editorProperties.getExportTimeoutMinutes(), TimeUnit.MINUTES);
			if (!finished) {
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
				throw new EditorRenderException("Editor render timed out");
			}

			int exitCode = process.exitValue();
			joinQuietly(progressFuture);
			String stderr = joinOutput(stderrFuture);

			if (handle.isCancelRequested()) {
				Files.deleteIfExists(outputMp4);
				throw new EditorRenderCancelledException();
			}
			if (exitCode != 0) {
				throw new EditorRenderException(buildFailureMessage(exitCode, stderr));
			}
			if (!Files.isRegularFile(outputMp4)) {
				throw new EditorRenderException("Render completed but output MP4 is missing");
			}
			long size = Files.size(outputMp4);
			if (size <= 0) {
				throw new EditorRenderException("Render completed but output MP4 is empty");
			}
			log.info(
					"Visual reorder completed projectId={} exportJobId={} sizeBytes={} durationMs={}",
					projectId,
					exportJobId,
					size,
					durationMillis
			);
			return size;
		} catch (EditorRenderException | ConcurrentEditorLimitException | EditorRenderCancelledException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new EditorRenderException("Failed to start editor render", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			throw new EditorRenderException("Editor render interrupted", ex);
		} finally {
			running.remove(projectId);
			if (acquiredHere) {
				releaseExportSlot();
			}
		}
	}

	List<String> buildCommand(
			Path sourceMp4,
			Path outputMp4,
			String filterComplex,
			long durationMillis,
			boolean hasAudio,
			String audioCodec
	) {
		return buildCommand(sourceMp4, outputMp4, filterComplex, durationMillis, durationMillis, hasAudio, audioCodec, null, List.of());
	}

	List<String> buildCommand(
			Path sourceMp4,
			Path outputMp4,
			String filterComplex,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan
	) {
		return buildCommand(sourceMp4, outputMp4, filterComplex, durationMillis, durationMillis, hasAudio, audioCodec, plan, List.of());
	}

	List<String> buildCommand(
			Path sourceMp4,
			Path outputMp4,
			String filterComplex,
			long durationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			List<VisualReorderFilterGraph.ImageLoopInput> imageInputs
	) {
		return buildCommand(
				sourceMp4,
				outputMp4,
				filterComplex,
				durationMillis,
				durationMillis,
				hasAudio,
				audioCodec,
				plan,
				imageInputs
		);
	}

	/**
	 * Discrete ProcessBuilder argv. Paths and {@code -filter_complex} are separate entries — never a shell string.
	 * Visual is {@code [vout]}. Locked audio is {@code original[0..outputDuration]}:
	 * {@code atrim}+AAC when output is shorter than source; otherwise {@code -map 0:a:0?} (copy if MP4-safe).
	 * Never {@code -shortest}. Never {@code atempo}.
	 */
	List<String> buildCommand(
			Path sourceMp4,
			Path outputMp4,
			String filterComplex,
			long outputDurationMillis,
			long sourceDurationMillis,
			boolean hasAudio,
			String audioCodec,
			EditorExportPlan plan,
			List<VisualReorderFilterGraph.ImageLoopInput> imageInputs
	) {
		String encoder = Optional.ofNullable(plan)
				.map(EditorExportPlan::settings)
				.map(settings -> settings.codec().ffmpegEncoder())
				.orElse(EditorExportCodec.H264.ffmpegEncoder());

		String imageFps = Optional.ofNullable(plan)
				.map(EditorExportPlan::outputFps)
				.map(VisualReorderFilterGraph::fpsLiteral)
				.orElse("30");

		boolean audioFiltered = EditorAudioExport.needsFilterTrim(
				hasAudio,
				outputDurationMillis,
				sourceDurationMillis,
				editorProperties.getCoverageEpsilonMillis()
		);
		String filter = filterComplex;
		if (audioFiltered) {
			if (VisualReorderFilterGraph.containsForbiddenAudioRetiming(filter)) {
				throw new EditorRenderException("Internal error: audio must not be re-timed in the visual graph");
			}
			filter = filter + ";" + EditorAudioExport.trimFilter(outputDurationMillis);
		}

		List<String> command = new ArrayList<>();
		command.add(mediaProperties.getFfmpegPath());
		command.add("-hide_banner");
		command.add("-y");
		command.add("-nostats");
		command.add("-progress");
		command.add("pipe:1");
		command.add("-i");
		command.add(sourceMp4.toAbsolutePath().toString());
		if (imageInputs != null) {
			for (VisualReorderFilterGraph.ImageLoopInput image : imageInputs) {
				command.add("-loop");
				command.add("1");
				command.add("-framerate");
				command.add(imageFps);
				command.add("-t");
				command.add(VisualReorderFilterGraph.seconds(image.durationMillis()));
				command.add("-i");
				command.add(image.path().toAbsolutePath().toString());
			}
		}
		command.add("-filter_complex");
		command.add(filter);
		command.add("-map");
		command.add("[vout]");
		if (hasAudio) {
			command.add("-map");
			command.add(EditorAudioExport.mapLabel(audioFiltered));
		}
		EditorEncodeProfile encode = editorProperties.encodeProfile(
				plan != null && plan.settings() != null ? plan.settings().quality() : null
		);
		command.add("-c:v");
		command.add(encoder);
		command.add("-preset");
		command.add(encode.preset());
		command.add("-crf");
		command.add(Integer.toString(encode.crf()));
		command.add("-pix_fmt");
		command.add("yuv420p");
		EditorAudioCodecPolicy.appendMp4AudioArgs(command, hasAudio, audioCodec, audioFiltered);
		if (outputDurationMillis > 0) {
			command.add("-t");
			command.add(VisualReorderFilterGraph.seconds(outputDurationMillis));
		}
		command.add("-movflags");
		command.add("+faststart");
		command.add(outputMp4.toAbsolutePath().toString());
		return command;
	}

	private void escalateStop(UUID projectId, Process process) {
		try {
			boolean exited = process.waitFor(mediaProperties.getStopTimeoutSeconds(), TimeUnit.SECONDS);
			if (exited || !process.isAlive()) {
				log.info("Editor FFmpeg exited after quit signal projectId={}", projectId);
				return;
			}
			log.warn(
					"Editor FFmpeg still alive after {}s quit wait; calling destroy() projectId={}",
					mediaProperties.getStopTimeoutSeconds(),
					projectId
			);
			process.destroy();
			boolean exitedAfterDestroy = process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
			if (exitedAfterDestroy || !process.isAlive()) {
				log.info("Editor FFmpeg exited after destroy() projectId={}", projectId);
				return;
			}
			log.warn("Editor FFmpeg still alive after destroy(); calling destroyForcibly() projectId={}", projectId);
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
			log.debug("Could not write quit signal to editor FFmpeg stdin: {}", ex.getMessage());
		}
	}

	private CompletableFuture<Void> readProgressAsync(
			UUID projectId,
			UUID exportJobId,
			InputStream inputStream,
			long totalMillis
	) {
		return CompletableFuture.runAsync(() -> {
			Map<String, String> block = new LinkedHashMap<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					FfmpegProgressParser.acceptLine(block, line).ifPresent(progress ->
							eventPublisher.publishEvent(new EditorProgressEvent(
									projectId,
									exportJobId,
									progress,
									totalMillis
							))
					);
				}
			} catch (IOException ex) {
				log.debug("Editor progress stream closed projectId={}: {}", projectId, ex.toString());
			}
		}, ioExecutor);
	}

	private CompletableFuture<String> readFullyAsync(InputStream inputStream) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException ex) {
				throw new EditorRenderException("Failed to read render process output", ex);
			}
		}, ioExecutor);
	}

	private static void joinQuietly(CompletableFuture<Void> future) {
		try {
			future.join();
		} catch (CompletionException ignored) {
			// progress drain is best-effort
		}
	}

	private static String joinOutput(CompletableFuture<String> future) {
		try {
			return future.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof EditorRenderException renderException) {
				throw renderException;
			}
			throw new EditorRenderException("Failed to read render process output", cause);
		}
	}

	private static String buildFailureMessage(int exitCode, String detail) {
		if (detail == null || detail.isBlank()) {
			return "Editor render failed with exit code " + exitCode;
		}
		String trimmed = UrlRedactor.redactInText(detail.trim());
		if (trimmed.length() > 500) {
			trimmed = trimmed.substring(0, 500) + "...";
		}
		return "Editor render failed with exit code " + exitCode + ": " + trimmed;
	}
}
