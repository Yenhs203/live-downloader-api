package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.enums.EditorSegmentType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds an FFmpeg {@code -filter_complex} graph that composes <strong>video only</strong>.
 * Controller/service never assemble the graph string; they call {@link #compile}.
 * <p>
 * Per visual clip:
 * <ul>
 *   <li>VIDEO — trim source range, reset PTS, apply {@code playbackRate}, then normalize when a canvas exists.</li>
 *   <li>IMAGE — no trim and no speed; duration is the looped input {@code -t} slot, then normalize.</li>
 * </ul>
 * Clips are concatenated ({@code concat=v=1:a=0}). Optional post-concat scale/fps apply when
 * clips were not already normalized. Audio is mapped outside this graph.
 * <p>
 * Input 0 is always the source MP4. IMAGE clips are extra looped inputs ({@code -loop 1 -t duration -i}).
 */
public final class VisualReorderFilterGraph {

	private VisualReorderFilterGraph() {
	}

	public record ImageLoopInput(int inputIndex, Path path, long durationMillis) {
	}

	public record CompiledVisualGraph(
			String filterComplex,
			List<ImageLoopInput> imageInputs,
			long expectedOutputDurationMillis
	) {
	}

	public static String build(List<EditorSegment> visualOrder, long padMillis) {
		return compile(visualOrder, Map.of(), padMillis, null).filterComplex();
	}

	public static String build(List<EditorSegment> visualOrder, long padMillis, EditorExportPlan plan) {
		return compile(visualOrder, Map.of(), padMillis, plan).filterComplex();
	}

	public static CompiledVisualGraph compile(
			List<EditorSegment> visualOrder,
			Map<UUID, Path> imageAssets,
			long padMillis,
			EditorExportPlan plan
	) {
		if (visualOrder == null || visualOrder.isEmpty()) {
			throw new IllegalArgumentException("visualOrder must not be empty");
		}
		List<Integer> inputIndexes = new ArrayList<>(visualOrder.size());
		List<ImageLoopInput> extras = new ArrayList<>();
		int nextInput = 1;
		Map<UUID, Path> assets = imageAssets == null ? Map.of() : imageAssets;
		for (EditorSegment segment : visualOrder) {
			if (segment.isImage()) {
				if (segment.assetId() == null || !assets.containsKey(segment.assetId())) {
					throw new IllegalArgumentException("IMAGE segment missing asset file: " + segment.assetId());
				}
				extras.add(new ImageLoopInput(nextInput, assets.get(segment.assetId()), segment.durationMillis()));
				inputIndexes.add(nextInput);
				nextInput++;
			} else {
				inputIndexes.add(0);
			}
		}
		boolean hasImages = !extras.isEmpty();
		boolean normalizeEachClip = hasImages || hasCanvas(plan);
		boolean shareTimebase = visualOrder.stream()
				.anyMatch(segment -> segment.isVideo() && !EditorPlaybackRate.isUnity(segment.playbackRate()));
		String graph = assemble(
				visualOrder,
				inputIndexes,
				padMillis,
				plan,
				normalizeEachClip,
				shareTimebase
		);
		return new CompiledVisualGraph(graph, List.copyOf(extras), visualDurationMillis(visualOrder));
	}

	private static boolean hasCanvas(EditorExportPlan plan) {
		return plan != null && plan.outputWidth() != null && plan.outputHeight() != null
				&& plan.outputWidth() > 0 && plan.outputHeight() > 0;
	}

	private static String assemble(
			List<EditorSegment> visualOrder,
			List<Integer> inputIndexes,
			long padMillis,
			EditorExportPlan plan,
			boolean normalizeEachClip,
			boolean shareTimebase
	) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < visualOrder.size(); i++) {
			parts.add(clipChain(visualOrder.get(i), inputIndexes.get(i), i, plan, normalizeEachClip, shareTimebase)
					.render());
		}
		parts.add(concat(visualOrder.size()));

		boolean postprocess = !normalizeEachClip && plan != null && (plan.scale() || plan.changeFps());
		boolean speedNormalize = shareTimebase && !normalizeEachClip;
		boolean moreAfterConcat = postprocess || speedNormalize;
		String current = "vcat";
		if (padMillis > 0) {
			String padLabel = moreAfterConcat ? "vpad" : "vout";
			parts.add("[vcat]tpad=stop_mode=clone:stop_duration=" + seconds(padMillis) + "[" + padLabel + "]");
			current = padLabel;
		}

		if (postprocess && plan.scale() && plan.outputWidth() != null && plan.outputHeight() != null) {
			parts.add("[" + current + "]" + letterbox(plan.outputWidth(), plan.outputHeight()) + "[vfit]");
			current = "vfit";
		}

		boolean applyFps = (postprocess && plan.changeFps() && plan.outputFps() != null)
				|| (speedNormalize && plan != null && plan.outputFps() != null);
		if (applyFps) {
			String fpsLabel = speedNormalize ? "vfps" : "vout";
			parts.add("[" + current + "]fps=" + fpsLiteral(plan.outputFps()) + "[" + fpsLabel + "]");
			current = fpsLabel;
		}

		if (speedNormalize) {
			parts.add("[" + current + "]setsar=1,format=yuv420p[vout]");
			current = "vout";
		}

		if (!"vout".equals(current)) {
			parts.add("[" + current + "]null[vout]");
		}
		return String.join(";", parts);
	}

	/**
	 * One visual clip: VIDEO trim → reset PTS → speed; IMAGE uses its duration slot only.
	 * Normalize (resolution + FPS + pixel format) is applied per clip when a canvas exists.
	 */
	private static ClipChain clipChain(
			EditorSegment segment,
			int inputIndex,
			int outputIndex,
			EditorExportPlan plan,
			boolean normalizeEachClip,
			boolean shareTimebase
	) {
		ClipChain chain = new ClipChain(inputIndex, outputIndex);
		if (segment.isVideo()) {
			chain.trim(segment.sourceStartMillis(), segment.sourceEndMillis());
			chain.resetPtsAndSpeed(segment.playbackRate(), shareTimebase);
		} else if (!normalizeEachClip) {
			throw new IllegalArgumentException("IMAGE clips require per-clip canvas normalization");
		}
		if (normalizeEachClip) {
			if (!hasCanvas(plan)) {
				throw new IllegalArgumentException("Normalized concat requires an export canvas (width/height)");
			}
			double fps = plan.outputFps() != null ? plan.outputFps() : 30.0d;
			chain.normalize(plan.outputWidth(), plan.outputHeight(), fps);
		}
		return chain;
	}

	private static String concat(int clipCount) {
		if (clipCount == 1) {
			return "[v0]null[vcat]";
		}
		StringBuilder labels = new StringBuilder();
		for (int i = 0; i < clipCount; i++) {
			labels.append("[v").append(i).append(']');
		}
		return labels.append("concat=n=").append(clipCount).append(":v=1:a=0[vcat]").toString();
	}

	/**
	 * Ordered FFmpeg filters for one clip. Joined with commas only at render time.
	 */
	static final class ClipChain {

		private final int inputIndex;
		private final int outputIndex;
		private final List<String> filters = new ArrayList<>();

		ClipChain(int inputIndex, int outputIndex) {
			this.inputIndex = inputIndex;
			this.outputIndex = outputIndex;
		}

		ClipChain trim(Long sourceStartMillis, Long sourceEndMillis) {
			long start = sourceStartMillis == null ? 0L : sourceStartMillis;
			long end = sourceEndMillis == null ? start : sourceEndMillis;
			filters.add("trim=start=" + seconds(start) + ":end=" + seconds(end));
			return this;
		}

		ClipChain resetPtsAndSpeed(double playbackRate, boolean shareTimebase) {
			filters.add(visualSetPts(playbackRate, shareTimebase));
			return this;
		}

		ClipChain normalize(int width, int height, double fps) {
			filters.add(fitCanvas(width, height, fps));
			return this;
		}

		String render() {
			return "[" + inputIndex + ":v]" + String.join(",", filters) + "[v" + outputIndex + "]";
		}
	}

	/**
	 * Visual speed only. {@code 1.0} keeps {@code PTS-STARTPTS}; otherwise {@code (PTS-STARTPTS)/rate}.
	 * {@code settb=AVTB} is added when any clip is sped so concat shares a timebase.
	 */
	static String visualSetPts(double playbackRate, boolean shareTimebase) {
		String setpts = EditorPlaybackRate.setpts(playbackRate);
		if (shareTimebase) {
			return setpts + ",settb=AVTB";
		}
		return setpts;
	}

	static String visualSetPts(EditorSegment segment, boolean resetTimebase) {
		double rate = segment == null ? EditorPlaybackRate.DEFAULT : segment.playbackRate();
		return visualSetPts(rate, resetTimebase);
	}

	static String visualSetPts(EditorSegment segment) {
		return visualSetPts(segment, segment != null && !EditorPlaybackRate.isUnity(segment.playbackRate()));
	}

	/**
	 * Letterbox onto the export canvas. Never stretches: scale down (or equal) then center-pad.
	 */
	static String letterbox(int width, int height) {
		return "scale=" + width + ":" + height
				+ ":force_original_aspect_ratio=decrease,pad="
				+ width + ":" + height
				+ ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1";
	}

	static String fitCanvas(int width, int height, double fps) {
		return letterbox(width, height)
				+ ",fps="
				+ fpsLiteral(fps)
				+ ",format=yuv420p,setpts=PTS-STARTPTS";
	}

	public static long visualDurationMillis(List<EditorSegment> visualOrder) {
		return EditorTimelineDurations.outputDurationMillis(visualOrder);
	}

	/**
	 * Forbidden audio re-timing / reorder. Locked {@code atrim}/{@code asetpts} is allowed
	 * on the combined graph; this flags {@code atempo} / {@code asetrate} / audio concat.
	 */
	public static boolean containsForbiddenAudioRetiming(String filterComplex) {
		if (filterComplex == null) {
			return false;
		}
		String lower = filterComplex.toLowerCase(Locale.ROOT);
		return lower.contains("atempo")
				|| lower.contains("asetrate")
				|| lower.contains("rubberband")
				|| (lower.contains("concat=n=") && lower.contains(":a=1"));
	}

	/**
	 * Visual-only compile must not touch audio. Combined graphs may append {@code [0:a]atrim...[aout]}.
	 */
	public static boolean visualGraphTouchesAudio(String filterComplex) {
		if (filterComplex == null) {
			return false;
		}
		String lower = filterComplex.toLowerCase(Locale.ROOT);
		return lower.contains("[0:a]")
				|| lower.contains("atrim")
				|| lower.contains("asetpts")
				|| containsForbiddenAudioRetiming(filterComplex);
	}

	/**
	 * @deprecated use {@link #containsForbiddenAudioRetiming(String)} or {@link #visualGraphTouchesAudio(String)}
	 */
	@Deprecated
	public static boolean containsAudioFilters(String filterComplex) {
		return visualGraphTouchesAudio(filterComplex);
	}

	/**
	 * FFmpeg trim literals only. Timeline math stays in integer milliseconds.
	 */
	public static String seconds(long millis) {
		return String.format(Locale.US, "%.3f", millis / 1000.0d);
	}

	public static String fpsLiteral(double fps) {
		if (fps == Math.rint(fps)) {
			return Integer.toString((int) Math.rint(fps));
		}
		return String.format(Locale.US, "%.3f", fps);
	}

	public static String describe(List<EditorSegment> visualOrder) {
		return visualOrder.stream()
				.map(VisualReorderFilterGraph::describeOne)
				.collect(Collectors.joining(" | "));
	}

	private static String describeOne(EditorSegment segment) {
		if (segment.type() == EditorSegmentType.IMAGE) {
			return segment.label() + "{asset=" + segment.assetId() + "}[" + segment.durationMillis() + "ms]";
		}
		String range = segment.label() + "[" + segment.sourceStartMillis() + "-" + segment.sourceEndMillis() + "]";
		if (EditorPlaybackRate.isUnity(segment.playbackRate())) {
			return range;
		}
		return range + "@" + String.format(Locale.US, "%.2fx", segment.playbackRate());
	}
}
