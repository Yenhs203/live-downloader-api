package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import com.vhmedia.livedownloader.exception.InvalidOutputDurationException;
import com.vhmedia.livedownloader.exception.InvalidSegmentBoundaryException;
import com.vhmedia.livedownloader.exception.InvalidSegmentTrimException;
import com.vhmedia.livedownloader.exception.InvalidSplitPositionException;
import com.vhmedia.livedownloader.exception.SegmentTooShortException;
import com.vhmedia.livedownloader.exception.SegmentsNotMergeableException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Visual timeline rules. Array order is output visual order. Audio is not part of this model.
 * <p>
 * Invariants (V1):
 * <ul>
 *   <li>VIDEO source ranges are valid timestamps ({@code 0 <= start < end <= sourceDuration}).</li>
 *   <li>VIDEO clips do not overlap and do not leave an <em>internal</em> source gap.
 *       Uncovered prefix/suffix (trim) is allowed.</li>
 *   <li>Clip order is the output visual order; merge/boundary keep a shared source cut
 *       (merge is the deterministic undo of split — left id is kept).</li>
 *   <li>{@code outputDuration > 0} and is {@code sum(visual durations after speed)} —
 *       it is <em>not</em> required to equal source duration.</li>
 *   <li>When audio is locked, {@code outputDuration <= available original audio}
 *       ({@link EditorTimelineDurations#assertFitsLockedAudio}).</li>
 * </ul>
 * Original trim bounds are not stored, so reset cannot restore a pre-trim range.
 */
@Component
public class EditorSegmentValidator {

	private final EditorProperties editorProperties;

	public EditorSegmentValidator(EditorProperties editorProperties) {
		this.editorProperties = editorProperties;
	}

	public List<EditorSegment> normalize(List<EditorSegment> visualOrder, long sourceDurationMillis) {
		return normalize(visualOrder, sourceDurationMillis, Set.of());
	}

	public List<EditorSegment> normalize(
			List<EditorSegment> visualOrder,
			long sourceDurationMillis,
			Set<UUID> knownAssetIds
	) {
		if (visualOrder == null || visualOrder.isEmpty()) {
			throw new InvalidEditorSegmentsException("At least one segment is required");
		}
		if (sourceDurationMillis <= 0) {
			throw new InvalidEditorSegmentsException("Source duration is unknown or invalid");
		}
		int maxSegments = editorProperties.getMaxSegments();
		if (visualOrder.size() > maxSegments) {
			throw new InvalidEditorSegmentsException("Too many segments (max " + maxSegments + ")");
		}

		int minSegmentMillis = editorProperties.getMinSegmentMillis();
		int epsilon = editorProperties.getCoverageEpsilonMillis();
		Set<UUID> assets = knownAssetIds == null ? Set.of() : knownAssetIds;
		List<EditorSegment> normalized = new ArrayList<>(visualOrder.size());
		Set<String> exactRanges = new HashSet<>();

		for (EditorSegment raw : visualOrder) {
			if (raw == null) {
				throw new InvalidEditorSegmentsException("Segment must not be null");
			}
			EditorSegment typed = raw.isImage()
					? normalizeImage(raw, minSegmentMillis, epsilon, assets)
					: normalizeVideo(raw, sourceDurationMillis, epsilon, minSegmentMillis, exactRanges, assets);
			normalized.add(typed);
		}

		List<EditorSegment> videos = normalized.stream().filter(EditorSegment::isVideo).toList();
		boolean hasImage = normalized.stream().anyMatch(EditorSegment::isImage);
		if (videos.isEmpty()) {
			if (!editorProperties.isImageSegmentsEnabled() || !hasImage) {
				throw new InvalidEditorSegmentsException("At least one VIDEO segment is required");
			}
		} else if (hasImage) {
			assertNoVideoOverlap(videos, sourceDurationMillis, epsilon);
		} else {
			assertNoInternalVideoGaps(videos, sourceDurationMillis, epsilon);
		}
		List<EditorSegment> labeled = assignLabels(normalized);
		EditorTimelineDurations.assertPositive(EditorTimelineDurations.outputDurationMillis(labeled));
		return labeled;
	}

	/**
	 * Neighbors on the <em>visual</em> timeline may be merged (undo-split) only when they are
	 * the same VIDEO asset, the same playback rate, and meet at the same source cut
	 * ({@code left.sourceEnd ≈ right.sourceStart}).
	 * <p>
	 * Reorder does not change source ranges. {@code A1 | A2 | B} can merge A1+A2;
	 * {@code A1 | B | A2} cannot merge A1 with B even though A1 and A2 are still
	 * source-contiguous. After reordering back to {@code A1 | A2 | B}, merge works again.
	 */
	public void assertMergeableNeighbors(EditorSegment left, EditorSegment right, UUID fallbackAssetId) {
		if (left == null || right == null) {
			throw new SegmentsNotMergeableException("There is no next clip to merge.");
		}
		if (!left.isVideo() || !right.isVideo()) {
			throw new SegmentsNotMergeableException("Only video clips that meet at the same cut can be merged.");
		}
		UUID leftAsset = left.assetId() != null ? left.assetId() : fallbackAssetId;
		UUID rightAsset = right.assetId() != null ? right.assetId() : fallbackAssetId;
		if (leftAsset == null || rightAsset == null || !leftAsset.equals(rightAsset)) {
			throw new SegmentsNotMergeableException("These clips come from different sources and cannot be merged.");
		}
		if (left.sourceStartMillis() == null || left.sourceEndMillis() == null
				|| right.sourceStartMillis() == null || right.sourceEndMillis() == null) {
			throw new SegmentsNotMergeableException("These clips cannot be merged.");
		}
		int epsilon = editorProperties.getCoverageEpsilonMillis();
		if (Math.abs(left.sourceEndMillis() - right.sourceStartMillis()) > epsilon) {
			throw new SegmentsNotMergeableException(
					"These clips do not meet at the same cut, so they cannot be merged."
			);
		}
		if (!EditorPlaybackRate.same(left.playbackRate(), right.playbackRate())) {
			throw new SegmentsNotMergeableException(
					"These clips have different playback rates and cannot be merged."
			);
		}
	}

	public boolean canMergeNext(EditorSegment left, EditorSegment right, UUID fallbackAssetId) {
		try {
			assertMergeableNeighbors(left, right, fallbackAssetId);
			return true;
		} catch (SegmentsNotMergeableException ex) {
			return false;
		}
	}

	/**
	 * Same rule as merge: visually adjacent VIDEO clips that meet at a source cut.
	 * After reorder {@code C(20..30) | A(0..10)} this is false — do not drag a shared boundary.
	 */
	public boolean canShareSourceBoundary(EditorSegment left, EditorSegment right, UUID fallbackAssetId) {
		return canMergeNext(left, right, fallbackAssetId);
	}

	public void assertShareableBoundaryNeighbors(EditorSegment left, EditorSegment right, UUID fallbackAssetId) {
		if (!canShareSourceBoundary(left, right, fallbackAssetId)) {
			throw new InvalidSegmentBoundaryException(
					"These clips do not share a source cut, so the boundary cannot be dragged."
			);
		}
	}

	/**
	 * Shared cut between two source-contiguous VIDEO neighbors. No gap, no overlap.
	 *
	 * @return boundary clamped to source duration when within epsilon of the end
	 */
	public long assertSharedBoundary(
			EditorSegment left,
			EditorSegment right,
			long boundaryMillis,
			long sourceDurationMillis,
			UUID fallbackAssetId
	) {
		assertShareableBoundaryNeighbors(left, right, fallbackAssetId);
		int minSegmentMillis = editorProperties.getMinSegmentMillis();
		int epsilon = editorProperties.getCoverageEpsilonMillis();
		if (sourceDurationMillis <= 0) {
			throw new InvalidSegmentBoundaryException("Source duration is unknown or invalid");
		}
		if (boundaryMillis < 0L || boundaryMillis > sourceDurationMillis + epsilon) {
			throw new InvalidSegmentBoundaryException("boundaryMillis is outside the source duration");
		}
		long boundary = Math.min(boundaryMillis, sourceDurationMillis);
		long leftStart = left.sourceStartMillis();
		long rightEnd = right.sourceEndMillis();
		if (boundary <= leftStart || boundary >= rightEnd) {
			throw new InvalidSegmentBoundaryException(
					"boundaryMillis must be strictly between the left clip start and the right clip end"
			);
		}
		double rate = left.playbackRate();
		if (EditorPlaybackRate.visualDurationMillis(boundary - leftStart, rate) < minSegmentMillis
				|| EditorPlaybackRate.visualDurationMillis(rightEnd - boundary, rate) < minSegmentMillis) {
			throw tooShort(minSegmentMillis);
		}
		return boundary;
	}

	/**
	 * Crop the current <em>visual output</em> to {@code [startMillis, endMillis)}.
	 * Canonical project-level trim (e.g. 27.167s → 25.000s with start=0, end=25000).
	 * Rewrites clip source ranges / IMAGE hold durations; no separate stored window.
	 * Does not expand. Audio stays {@code original[0 .. newOutput]}.
	 */
	public List<EditorSegment> cropToOutputRange(List<EditorSegment> visualOrder, long startMillis, long endMillis) {
		if (visualOrder == null || visualOrder.isEmpty()) {
			throw new InvalidOutputDurationException("Output duration must be greater than 0");
		}
		int epsilon = editorProperties.getCoverageEpsilonMillis();
		long output = EditorTimelineDurations.outputDurationMillis(visualOrder);
		if (startMillis < 0L || endMillis <= startMillis) {
			throw new InvalidSegmentTrimException("startMillis must be ≥ 0 and strictly less than endMillis");
		}
		if (startMillis >= output) {
			throw new InvalidSegmentTrimException("startMillis is outside the current output duration");
		}
		if (endMillis > output + epsilon) {
			throw new InvalidSegmentTrimException("endMillis cannot exceed the current output duration");
		}
		long end = Math.min(endMillis, output);
		List<EditorSegment> kept = new ArrayList<>();
		long cursor = 0L;
		for (EditorSegment segment : visualOrder) {
			long clipStart = cursor;
			long clipEnd = cursor + segment.durationMillis();
			cursor = clipEnd;
			if (clipEnd <= startMillis) {
				continue;
			}
			if (clipStart >= end) {
				break;
			}
			long keepFrom = Math.max(clipStart, startMillis) - clipStart;
			long keepTo = Math.min(clipEnd, end) - clipStart;
			kept.add(cropClipVisual(segment, keepFrom, keepTo));
		}
		if (kept.isEmpty()) {
			throw new InvalidOutputDurationException("Output duration must be greater than 0");
		}
		return kept;
	}

	private EditorSegment cropClipVisual(EditorSegment segment, long keepVisualStart, long keepVisualEnd) {
		long visual = segment.durationMillis();
		long keepStart = Math.max(0L, keepVisualStart);
		long keepEnd = Math.min(visual, keepVisualEnd);
		if (keepEnd <= keepStart) {
			throw new InvalidSegmentTrimException("Output range does not keep any of this clip");
		}
		assertMinDuration(keepEnd - keepStart);
		if (keepStart == 0L && keepEnd >= visual) {
			return segment;
		}
		if (segment.isImage()) {
			return EditorSegment.image(
					segment.id(),
					segment.label(),
					segment.assetId(),
					keepEnd - keepStart,
					segment.sourceStartMillis(),
					segment.sourceEndMillis()
			);
		}
		long sourceStart = segment.sourceStartMillis() == null ? 0L : segment.sourceStartMillis();
		long sourceEnd = segment.sourceEndMillis() == null ? sourceStart + visual : segment.sourceEndMillis();
		double rate = segment.playbackRate();
		long newStart = keepStart <= 0L ? sourceStart : sourceStart + sourceOffset(keepStart, rate);
		long newEnd = keepEnd >= visual ? sourceEnd : sourceStart + sourceOffset(keepEnd, rate);
		if (newEnd <= newStart) {
			throw new InvalidSegmentTrimException("Output range does not keep any of this clip");
		}
		assertMinDuration(EditorPlaybackRate.visualDurationMillis(newEnd - newStart, rate));
		return EditorSegment.video(
				segment.id(),
				segment.label(),
				newStart,
				newEnd,
				segment.assetId(),
				rate
		);
	}

	private static long sourceOffset(long visualOffsetMillis, double playbackRate) {
		if (visualOffsetMillis <= 0L) {
			return 0L;
		}
		if (EditorPlaybackRate.isUnity(playbackRate)) {
			return visualOffsetMillis;
		}
		return Math.round(visualOffsetMillis * EditorPlaybackRate.normalize(playbackRate));
	}

	public void assertSplitPoint(Long sourceStartMillis, long atMillis, Long sourceEndMillis) {
		assertSplitPoint(sourceStartMillis, atMillis, sourceEndMillis, EditorPlaybackRate.DEFAULT);
	}

	public void assertSplitPoint(Long sourceStartMillis, long atMillis, Long sourceEndMillis, double playbackRate) {
		if (sourceStartMillis == null || sourceEndMillis == null) {
			throw new InvalidSplitPositionException("Only VIDEO segments can be split");
		}
		long start = sourceStartMillis;
		long end = sourceEndMillis;
		int minSegmentMillis = editorProperties.getMinSegmentMillis();
		if (atMillis <= start || atMillis >= end) {
			throw new InvalidSplitPositionException(
					"atMillis must be strictly between sourceStartMillis and sourceEndMillis"
			);
		}
		if (EditorPlaybackRate.visualDurationMillis(atMillis - start, playbackRate) < minSegmentMillis
				|| EditorPlaybackRate.visualDurationMillis(end - atMillis, playbackRate) < minSegmentMillis) {
			throw tooShort(minSegmentMillis);
		}
	}

	public List<EditorSegment> defaultFullSpan(long sourceDurationMillis) {
		if (sourceDurationMillis <= 0) {
			throw new InvalidEditorSegmentsException("Source duration is unknown or invalid");
		}
		return List.of(EditorSegment.video(UUID.randomUUID().toString(), "A", 0L, sourceDurationMillis));
	}

	private EditorSegment normalizeVideo(
			EditorSegment raw,
			long sourceDurationMillis,
			int epsilon,
			int minSegmentMillis,
			Set<String> exactRanges,
			Set<UUID> knownAssetIds
	) {
		if (raw.sourceStartMillis() == null || raw.sourceEndMillis() == null) {
			throw new InvalidEditorSegmentsException("VIDEO segments require sourceStartMillis and sourceEndMillis");
		}
		long start = raw.sourceStartMillis();
		long end = raw.sourceEndMillis();
		if (start < 0L) {
			throw new InvalidEditorSegmentsException("sourceStartMillis must be >= 0");
		}
		if (end < 0L) {
			throw new InvalidEditorSegmentsException("sourceEndMillis must be >= 0");
		}
		if (end > sourceDurationMillis + epsilon) {
			throw new InvalidEditorSegmentsException(
					"sourceEndMillis exceeds source duration (" + end + " > " + sourceDurationMillis + ")"
			);
		}
		if (end > sourceDurationMillis) {
			end = sourceDurationMillis;
		}
		if (end <= start) {
			throw new InvalidEditorSegmentsException("sourceEndMillis must be greater than sourceStartMillis");
		}
		if (raw.assetId() != null && !knownAssetIds.isEmpty() && !knownAssetIds.contains(raw.assetId())) {
			throw new InvalidEditorSegmentsException("VIDEO assetId does not belong to this project: " + raw.assetId());
		}
		long source = end - start;
		double rate = EditorPlaybackRate.normalize(raw.playbackRate());
		long visual = EditorPlaybackRate.visualDurationMillis(source, rate);
		if (visual < minSegmentMillis) {
			throw tooShort(minSegmentMillis);
		}
		String rangeKey = start + ":" + end;
		if (!exactRanges.add(rangeKey)) {
			throw new InvalidEditorSegmentsException(
					"Duplicate segments are not allowed in V1 (range " + start + "-" + end + " ms)"
			);
		}
		return new EditorSegment(
				raw.id() == null || raw.id().isBlank() ? UUID.randomUUID().toString() : raw.id(),
				raw.label(),
				EditorSegmentType.VIDEO,
				start,
				end,
				raw.assetId(),
				source,
				rate
		);
	}

	private EditorSegment normalizeImage(EditorSegment raw, int minSegmentMillis, int epsilon, Set<UUID> knownAssetIds) {
		if (!editorProperties.isImageSegmentsEnabled()) {
			throw new InvalidEditorSegmentsException(
					"IMAGE segments are not enabled (set EDITOR_IMAGE_SEGMENTS_ENABLED=true for Phase 1B)"
			);
		}
		if (raw.assetId() == null) {
			throw new InvalidEditorSegmentsException("IMAGE segments require assetId");
		}
		if (!knownAssetIds.contains(raw.assetId())) {
			throw new InvalidEditorSegmentsException("IMAGE assetId does not belong to this project: " + raw.assetId());
		}
		long duration = raw.durationMillis();
		if (duration < minSegmentMillis) {
			throw tooShort(minSegmentMillis);
		}
		Long start = raw.sourceStartMillis();
		Long end = raw.sourceEndMillis();
		if (start != null ^ end != null) {
			throw new InvalidEditorSegmentsException(
					"IMAGE replacement slots require both sourceStartMillis and sourceEndMillis"
			);
		}
		if (start != null) {
			if (end <= start) {
				throw new InvalidEditorSegmentsException("sourceEndMillis must be greater than sourceStartMillis");
			}
		}
		return new EditorSegment(
				raw.id() == null || raw.id().isBlank() ? UUID.randomUUID().toString() : raw.id(),
				raw.label(),
				EditorSegmentType.IMAGE,
				start,
				end,
				raw.assetId(),
				duration,
				EditorPlaybackRate.DEFAULT
		);
	}

	/**
	 * Shrink a first/last VIDEO clip. Does not move a shared cut (use boundary) and does not expand.
	 */
	public EditorSegment assertTrim(
			EditorSegment current,
			long sourceStartMillis,
			long sourceEndMillis,
			boolean firstOnTimeline,
			boolean lastOnTimeline,
			long sourceDurationMillis
	) {
		if (current == null || !current.isVideo()) {
			throw new InvalidSegmentTrimException("Only VIDEO clips can be trimmed");
		}
		if (current.sourceStartMillis() == null || current.sourceEndMillis() == null) {
			throw new InvalidSegmentTrimException("VIDEO clips require a source range to trim");
		}
		int epsilon = editorProperties.getCoverageEpsilonMillis();
		if (sourceEndMillis <= sourceStartMillis) {
			throw new InvalidSegmentTrimException("sourceEndMillis must be greater than sourceStartMillis");
		}
		if (sourceStartMillis < 0L || sourceEndMillis > sourceDurationMillis + epsilon) {
			throw new InvalidSegmentTrimException("Trim range is outside the source duration");
		}
		long start = sourceStartMillis;
		long end = Math.min(sourceEndMillis, sourceDurationMillis);
		assertMinDuration(EditorPlaybackRate.visualDurationMillis(end - start, current.playbackRate()));
		long oldStart = current.sourceStartMillis();
		long oldEnd = current.sourceEndMillis();
		if (start < oldStart - epsilon || end > oldEnd + epsilon) {
			throw new InvalidSegmentTrimException(
					"Trim can only shorten this clip. Use the shared-cut API to move a boundary."
			);
		}
		boolean startChanged = Math.abs(start - oldStart) > epsilon;
		boolean endChanged = Math.abs(end - oldEnd) > epsilon;
		if (startChanged && !firstOnTimeline) {
			throw new InvalidSegmentTrimException(
					"Only the first clip can trim its left edge. Use the shared-cut API for a boundary between clips."
			);
		}
		if (endChanged && !lastOnTimeline) {
			throw new InvalidSegmentTrimException(
					"Only the last clip can trim its right edge. Use the shared-cut API for a boundary between clips."
			);
		}
		UUID assetId = current.assetId();
		return EditorSegment.video(current.id(), current.label(), start, end, assetId, current.playbackRate());
	}

	public void assertMinDuration(long durationMillis) {
		int minSegmentMillis = editorProperties.getMinSegmentMillis();
		if (durationMillis < minSegmentMillis) {
			throw tooShort(minSegmentMillis);
		}
	}

	private static SegmentTooShortException tooShort(int minSegmentMillis) {
		return new SegmentTooShortException(
				"Each clip must be at least " + minSegmentMillis + " ms"
		);
	}

	/**
	 * VIDEO clips may leave an uncovered source prefix/suffix (trim). Internal gaps and overlaps are rejected.
	 */
	private static void assertNoInternalVideoGaps(List<EditorSegment> videos, long sourceDurationMillis, int epsilon) {
		List<EditorSegment> bySource = sortedVideos(videos);
		Long previousEnd = null;
		for (EditorSegment segment : bySource) {
			if (previousEnd == null) {
				previousEnd = segment.sourceEndMillis();
				continue;
			}
			long gap = segment.sourceStartMillis() - previousEnd;
			if (Math.abs(gap) <= epsilon) {
				previousEnd = Math.max(previousEnd, segment.sourceEndMillis());
				continue;
			}
			if (gap > 0L) {
				throw new InvalidEditorSegmentsException(
						"Clips must not leave a gap between source ranges (gap at " + previousEnd + " ms)"
				);
			}
			throw new InvalidEditorSegmentsException(
					"V1 does not allow overlapping or duplicate source ranges (overlap at "
							+ segment.sourceStartMillis() + " ms)"
			);
		}
		if (previousEnd != null && previousEnd - sourceDurationMillis > epsilon) {
			throw new InvalidEditorSegmentsException(
					"VIDEO sourceEndMillis exceeds source duration (" + previousEnd + " > " + sourceDurationMillis + ")"
			);
		}
	}

	private static void assertNoVideoOverlap(List<EditorSegment> videos, long sourceDurationMillis, int epsilon) {
		List<EditorSegment> bySource = sortedVideos(videos);
		long expectedStart = 0L;
		for (EditorSegment segment : bySource) {
			long gap = segment.sourceStartMillis() - expectedStart;
			if (gap < -epsilon) {
				throw new InvalidEditorSegmentsException(
						"V1 does not allow overlapping or duplicate source ranges (overlap at "
								+ segment.sourceStartMillis() + " ms)"
				);
			}
			expectedStart = Math.max(expectedStart, segment.sourceEndMillis());
		}
		if (expectedStart - sourceDurationMillis > epsilon) {
			throw new InvalidEditorSegmentsException(
					"VIDEO sourceEndMillis exceeds source duration (" + expectedStart + " > " + sourceDurationMillis + ")"
			);
		}
	}

	private static List<EditorSegment> sortedVideos(List<EditorSegment> videos) {
		return videos.stream()
				.sorted(Comparator
						.comparingLong((EditorSegment segment) -> segment.sourceStartMillis())
						.thenComparingLong(segment -> segment.sourceEndMillis()))
				.toList();
	}

	/**
	 * VIDEO labels A, B, C... follow source order. IMAGE keeps its label or becomes IMG, IMG2, ...
	 */
	static List<EditorSegment> assignLabels(List<EditorSegment> visualOrder) {
		List<EditorSegment> videos = visualOrder.stream()
				.filter(EditorSegment::isVideo)
				.sorted(Comparator.comparingLong(segment -> segment.sourceStartMillis()))
				.toList();
		java.util.Map<String, String> idToLabel = new java.util.HashMap<>();
		for (int i = 0; i < videos.size(); i++) {
			EditorSegment segment = videos.get(i);
			String label = (segment.label() != null && !segment.label().isBlank())
					? segment.label().trim()
					: indexToLabel(i);
			idToLabel.put(segment.id(), label);
		}
		int imageIndex = 0;
		for (EditorSegment segment : visualOrder) {
			if (!segment.isImage()) {
				continue;
			}
			String label = (segment.label() != null && !segment.label().isBlank())
					? segment.label().trim()
					: (imageIndex == 0 ? "IMG" : "IMG" + (imageIndex + 1));
			idToLabel.put(segment.id(), label);
			imageIndex++;
		}
		List<EditorSegment> labeled = new ArrayList<>(visualOrder.size());
		for (EditorSegment segment : visualOrder) {
			labeled.add(new EditorSegment(
					segment.id(),
					idToLabel.get(segment.id()),
					segment.type(),
					segment.sourceStartMillis(),
					segment.sourceEndMillis(),
					segment.assetId(),
					segment.declaredDurationMillis(),
					segment.playbackRate()
			));
		}
		return labeled;
	}

	static String indexToLabel(int index) {
		StringBuilder builder = new StringBuilder();
		int remaining = index;
		do {
			builder.append((char) ('A' + (remaining % 26)));
			remaining = remaining / 26 - 1;
		} while (remaining >= 0);
		return builder.reverse().toString().toUpperCase(Locale.ROOT);
	}
}
