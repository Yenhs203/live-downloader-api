package com.vhmedia.livedownloader.editor;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import com.vhmedia.livedownloader.exception.InvalidSegmentBoundaryException;
import com.vhmedia.livedownloader.exception.InvalidSegmentTrimException;
import com.vhmedia.livedownloader.exception.InvalidSplitPositionException;
import com.vhmedia.livedownloader.exception.SegmentTooShortException;
import com.vhmedia.livedownloader.exception.SegmentsNotMergeableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditorSegmentValidatorTest {

	private EditorSegmentValidator validator;

	@BeforeEach
	void setUp() {
		EditorProperties properties = new EditorProperties();
		properties.setMaxSegments(50);
		properties.setMinSegmentMillis(100);
		properties.setCoverageEpsilonMillis(50);
		validator = new EditorSegmentValidator(properties);
	}

	@Test
	void cadbReorderKeepsValidSourceRangesWithoutRequiringOutputToEqualSource() {
		List<EditorSegment> visual = List.of(
				seg("C", 2000, 3000),
				seg("A", 0, 1000),
				seg("D", 3000, 4000),
				seg("B", 1000, 2000)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 4000);

		assertThat(normalized).extracting(EditorSegment::label).containsExactly("C", "A", "D", "B");
		assertThat(normalized).extracting(EditorSegment::sourceStartMillis)
				.containsExactly(2000L, 0L, 3000L, 1000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(normalized)).isEqualTo(4000);
	}

	@Test
	void rejectsEmptyTimelineAndUnknownDuration() {
		assertThatThrownBy(() -> validator.normalize(List.of(), 4000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("At least one");
		assertThatThrownBy(() -> validator.normalize(List.of(seg("A", 0, 1000)), 0))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("duration");
	}

	@Test
	void acceptsReorderedCompletePartition() {
		List<EditorSegment> visual = List.of(
				seg("C", 2000, 3000),
				seg("A", 0, 1000),
				seg("D", 3000, 4000),
				seg("B", 1000, 2000)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 4000);

		assertThat(normalized).hasSize(4);
		assertThat(normalized.get(0).label()).isEqualTo("C");
		assertThat(normalized.get(0).sourceStartMillis()).isEqualTo(2000);
		assertThat(normalized.get(0).sourceEndMillis()).isEqualTo(3000);
		assertThat(normalized.get(1).label()).isEqualTo("A");
		assertThat(normalized.get(2).label()).isEqualTo("D");
		assertThat(normalized.get(3).label()).isEqualTo("B");
		assertThat(normalized.stream().mapToLong(EditorSegment::durationMillis).sum()).isEqualTo(4000);
	}

	@Test
	void specExampleCadbPreservesSourceDuration() {
		// Source 0..60s split into A[0-10] B[10-25] C[25-40] D[40-60], visual order C A D B.
		List<EditorSegment> visual = List.of(
				seg("C", 25_000, 40_000),
				seg("A", 0, 10_000),
				seg("D", 40_000, 60_000),
				seg("B", 10_000, 25_000)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 60_000);

		assertThat(normalized).extracting(EditorSegment::label).containsExactly("C", "A", "D", "B");
		assertThat(normalized).extracting(EditorSegment::sourceStartMillis)
				.containsExactly(25_000L, 0L, 40_000L, 10_000L);
		assertThat(normalized).extracting(EditorSegment::sourceEndMillis)
				.containsExactly(40_000L, 10_000L, 60_000L, 25_000L);
		assertThat(normalized).extracting(EditorSegment::durationMillis)
				.containsExactly(15_000L, 10_000L, 20_000L, 15_000L);
		assertThat(normalized.stream().mapToLong(EditorSegment::durationMillis).sum()).isEqualTo(60_000);
	}

	@Test
	void rejectsDuplicateSegment() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 2000),
				seg("A-copy", 0, 2000)
		), 2000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("Duplicate");
	}

	@Test
	void rejectsInternalGapWhenMiddleClipIsMissing() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 10_000),
				seg("C", 25_000, 40_000),
				seg("D", 40_000, 60_000)
		), 60_000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("gap");
	}

	@Test
	void rejectsDuplicatingThatChangesDuration() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 10_000),
				seg("B", 10_000, 25_000),
				seg("C", 25_000, 40_000),
				seg("D", 40_000, 60_000),
				seg("A2", 0, 10_000)
		), 60_000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("Duplicate");
	}

	@Test
	void phase1aRejectsImageSegments() {
		UUID assetId = UUID.randomUUID();
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 10_000),
				EditorSegment.image("img", "IMG", assetId, 50_000)
		), 60_000, Set.of(assetId)))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("not enabled");
	}

	@Test
	void phase1bAllowsImageToReplaceVideoSlotOfEqualDuration() {
		EditorProperties properties = new EditorProperties();
		properties.setMaxSegments(50);
		properties.setMinSegmentMillis(100);
		properties.setCoverageEpsilonMillis(50);
		properties.setImageSegmentsEnabled(true);
		EditorSegmentValidator phase1b = new EditorSegmentValidator(properties);
		UUID assetId = UUID.randomUUID();

		List<EditorSegment> visual = List.of(
				seg("C", 25_000, 40_000),
				seg("A", 0, 10_000),
				seg("D", 40_000, 60_000),
				EditorSegment.image("img", "IMG", assetId, 15_000)
		);

		List<EditorSegment> normalized = phase1b.normalize(visual, 60_000, Set.of(assetId));

		assertThat(normalized).extracting(EditorSegment::label).containsExactly("C", "A", "D", "IMG");
		assertThat(normalized.get(3).isImage()).isTrue();
		assertThat(normalized.get(3).assetId()).isEqualTo(assetId);
		assertThat(normalized.get(3).durationMillis()).isEqualTo(15_000);
		assertThat(normalized.stream().mapToLong(EditorSegment::durationMillis).sum()).isEqualTo(60_000);
	}

	@Test
	void phase1bImageReplacementKeepsVideoSlotMetadata() {
		EditorProperties properties = new EditorProperties();
		properties.setImageSegmentsEnabled(true);
		EditorSegmentValidator phase1b = new EditorSegmentValidator(properties);
		UUID assetId = UUID.randomUUID();

		List<EditorSegment> visual = List.of(
				seg("A", 0, 10_000),
				EditorSegment.image("img", "B", assetId, 10_000, 10_000L, 20_000L),
				seg("C", 20_000, 30_000)
		);

		List<EditorSegment> normalized = phase1b.normalize(visual, 30_000, Set.of(assetId));

		assertThat(normalized.get(1).isImage()).isTrue();
		assertThat(normalized.get(1).sourceStartMillis()).isEqualTo(10_000L);
		assertThat(normalized.get(1).sourceEndMillis()).isEqualTo(20_000L);
		assertThat(normalized.get(1).durationMillis()).isEqualTo(10_000);
		assertThat(normalized.stream().mapToLong(EditorSegment::durationMillis).sum()).isEqualTo(30_000);
	}

	@Test
	void phase1bAllowsImageVisualDurationDifferentFromSourceRange() {
		EditorProperties properties = new EditorProperties();
		properties.setMaxSegments(50);
		properties.setMinSegmentMillis(100);
		properties.setCoverageEpsilonMillis(50);
		properties.setImageSegmentsEnabled(true);
		EditorSegmentValidator phase1b = new EditorSegmentValidator(properties);
		UUID assetId = UUID.randomUUID();

		List<EditorSegment> visual = List.of(
				seg("A", 0, 10_000),
				EditorSegment.image("img", "IMG", assetId, 5_000, 10_000L, 20_000L),
				seg("C", 20_000, 30_000)
		);

		List<EditorSegment> normalized = phase1b.normalize(visual, 30_000, Set.of(assetId));

		assertThat(normalized.get(1).isImage()).isTrue();
		assertThat(normalized.get(1).durationMillis()).isEqualTo(5_000);
		assertThat(normalized.get(1).sourceStartMillis()).isEqualTo(10_000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(normalized)).isEqualTo(25_000);
	}

	@Test
	void acceptsCoverageWithinEpsilon() {
		List<EditorSegment> visual = List.of(
				seg("A", 0, 2000),
				seg("B", 2000, 3995)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 4000);

		assertThat(normalized).hasSize(2);
		assertThat(Math.abs(normalized.stream().mapToLong(EditorSegment::durationMillis).sum() - 4000)).isLessThanOrEqualTo(50);
	}

	@Test
	void assignsSourceOrderLabelsWhenMissing() {
		List<EditorSegment> visual = List.of(
				seg(null, 2000, 4000),
				seg(null, 0, 2000)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 4000);

		assertThat(normalized.get(0).label()).isEqualTo("B");
		assertThat(normalized.get(1).label()).isEqualTo("A");
	}

	@Test
	void rejectsOverlap() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 1500),
				seg("B", 1000, 2000)
		), 2000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("overlap");
	}

	@Test
	void rejectsGap() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 1000),
				seg("B", 1500, 2000)
		), 2000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("gap");
	}

	@Test
	void acceptsUncoveredTailAfterTrim() {
		List<EditorSegment> visual = List.of(
				seg("A", 0, 20_000),
				seg("D", 20_000, 25_000)
		);

		List<EditorSegment> normalized = validator.normalize(visual, 27_167);

		assertThat(EditorTimelineDurations.outputDurationMillis(normalized)).isEqualTo(25_000);
		assertThat(normalized.get(1).sourceEndMillis()).isEqualTo(25_000);
	}

	@Test
	void rejectsTooShortSegment() {
		assertThatThrownBy(() -> validator.normalize(List.of(
				seg("A", 0, 50),
				seg("B", 50, 2000)
		), 2000))
				.isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
	}

	@Test
	void defaultFullSpanCoversDuration() {
		List<EditorSegment> segments = validator.defaultFullSpan(3500);
		assertThat(segments).hasSize(1);
		assertThat(segments.getFirst().sourceStartMillis()).isZero();
		assertThat(segments.getFirst().sourceEndMillis()).isEqualTo(3500);
		assertThat(segments.getFirst().durationMillis()).isEqualTo(3500);
		assertThat(segments.getFirst().label()).isEqualTo("A");
	}

	@Test
	void indexToLabelUsesExcelStyleLetters() {
		assertThat(EditorSegmentValidator.indexToLabel(0)).isEqualTo("A");
		assertThat(EditorSegmentValidator.indexToLabel(25)).isEqualTo("Z");
		assertThat(EditorSegmentValidator.indexToLabel(26)).isEqualTo("AA");
	}

	@Test
	void acceptsSplitPointInsideSegment() {
		validator.assertSplitPoint(0L, 12_000, 30_000L);
		validator.assertSplitPoint(10_000L, 18_000, 25_000L);
	}

	@Test
	void rejectsSplitOnOrOutsideBoundary() {
		assertThatThrownBy(() -> validator.assertSplitPoint(0L, 0, 30_000L))
				.isInstanceOf(InvalidSplitPositionException.class)
				.hasMessageContaining("strictly between");
		assertThatThrownBy(() -> validator.assertSplitPoint(0L, 30_000, 30_000L))
				.isInstanceOf(InvalidSplitPositionException.class)
				.hasMessageContaining("strictly between");
	}

	@Test
	void rejectsSplitTooCloseToBoundary() {
		assertThatThrownBy(() -> validator.assertSplitPoint(0L, 50, 30_000L))
				.isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
		assertThatThrownBy(() -> validator.assertSplitPoint(0L, 29_950, 30_000L))
				.isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
	}

	@Test
	void rejectsSplitOfImageSegment() {
		assertThatThrownBy(() -> validator.assertSplitPoint(null, 1000, null))
				.isInstanceOf(InvalidSplitPositionException.class)
				.hasMessageContaining("VIDEO");
	}

	@Test
	void mergeJoinsZeroToFiveWithFiveToTen() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5_000, assetId);
		EditorSegment right = EditorSegment.video("b", "B", 5_000, 10_000, assetId);
		validator.assertMergeableNeighbors(left, right, assetId);
	}

	@Test
	void mergeRejectsZeroToFivePlusTenToFifteen() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5_000, assetId);
		EditorSegment right = EditorSegment.video("c", "C", 10_000, 15_000, assetId);
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(left, right, assetId))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
	}

	@Test
	void mergeAllowsTinyTimestampSlack() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5002, assetId);
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, assetId);
		validator.assertMergeableNeighbors(left, right, assetId);
	}

	@Test
	void mergeRejectsNonContiguousSource() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId);
		EditorSegment right = EditorSegment.video("c", "C", 20_000, 25_000, assetId);
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(left, right, assetId))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
	}

	@Test
	void mergeRejectsImageAndVideo() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId);
		EditorSegment right = EditorSegment.image("img", "IMG", assetId, 5000, 5000L, 10_000L);
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(left, right, assetId))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("video");
	}

	@Test
	void mergeRejectsDifferentAssets() {
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, UUID.randomUUID());
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, UUID.randomUUID());
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(left, right, UUID.randomUUID()))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("different sources");
	}

	@Test
	void mergeRejectsDifferentPlaybackRates() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId, 2.0d);
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, assetId, 1.0d);
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(left, right, assetId))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("playback");
	}

	@Test
	void mergeFollowsVisualNeighborsNotSourceLineageAfterReorder() {
		UUID assetId = UUID.randomUUID();
		EditorSegment a1 = EditorSegment.video("a1", "A1", 0, 5_000, assetId);
		EditorSegment a2 = EditorSegment.video("a2", "A2", 5_000, 10_000, assetId);
		EditorSegment b = EditorSegment.video("b", "B", 10_000, 20_000, assetId);

		assertThat(validator.canMergeNext(a1, a2, assetId)).isTrue();
		assertThat(validator.canShareSourceBoundary(a1, a2, assetId)).isTrue();
		assertThat(validator.canMergeNext(a1, b, assetId)).isFalse();
		assertThat(validator.canMergeNext(b, a2, assetId)).isFalse();
		assertThat(validator.canShareSourceBoundary(a1, b, assetId)).isFalse();
		assertThat(validator.canShareSourceBoundary(b, a2, assetId)).isFalse();
		assertThatThrownBy(() -> validator.assertMergeableNeighbors(a1, b, assetId))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
		assertThat(validator.canMergeNext(a1, a2, assetId)).isTrue();
	}

	@Test
	void rejectsNegativeAndInvertedSourceTimestamps() {
		assertThatThrownBy(() -> validator.normalize(List.of(seg("A", -1, 1000)), 1000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("sourceStartMillis");
		assertThatThrownBy(() -> validator.normalize(List.of(seg("A", 1500, 1000)), 2000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("greater than");
		assertThatThrownBy(() -> validator.normalize(List.of(seg("A", 0, 5000)), 2000))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("exceeds");
	}

	@Test
	void twoXShortensVisualDuration() {
		EditorSegment clip = EditorSegment.video("a", "A", 10_000, 20_000, null, 2.0d);
		List<EditorSegment> normalized = validator.normalize(List.of(
				seg("A", 0, 10_000),
				clip,
				seg("C", 20_000, 30_000)
		), 30_000);

		assertThat(normalized.get(1).playbackRate()).isEqualTo(2.0d);
		assertThat(normalized.get(1).durationMillis()).isEqualTo(5_000);
		assertThat(EditorTimelineDurations.outputDurationMillis(normalized)).isEqualTo(25_000);
	}

	@Test
	void trimPreservesPlaybackRate() {
		EditorSegment last = EditorSegment.video("d", "D", 20_000, 27_167, null, 2.0d);
		EditorSegment trimmed = validator.assertTrim(last, 20_000, 25_000, false, true, 27_167);
		assertThat(trimmed.playbackRate()).isEqualTo(2.0d);
		assertThat(trimmed.durationMillis()).isEqualTo(2_500);
	}

	@Test
	void boundaryMovesSharedCut() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId);
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, assetId);
		assertThat(validator.assertSharedBoundary(left, right, 6000, 10_000, assetId)).isEqualTo(6000);
	}

	@Test
	void boundaryRejectsOutsidePair() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId);
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, assetId);
		assertThatThrownBy(() -> validator.assertSharedBoundary(left, right, 0, 10_000, assetId))
				.isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("strictly between");
		assertThatThrownBy(() -> validator.assertSharedBoundary(left, right, 10_000, 10_000, assetId))
				.isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("strictly between");
		assertThatThrownBy(() -> validator.assertSharedBoundary(left, right, 20_000, 10_000, assetId))
				.isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("outside");
	}

	@Test
	void boundaryRejectsPieceShorterThanMinimum() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("a", "A", 0, 5000, assetId);
		EditorSegment right = EditorSegment.video("b", "B", 5000, 10_000, assetId);
		assertThatThrownBy(() -> validator.assertSharedBoundary(left, right, 50, 10_000, assetId))
				.isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
	}

	@Test
	void shareableBoundaryRejectsReorderedNonContiguousNeighbors() {
		UUID assetId = UUID.randomUUID();
		EditorSegment left = EditorSegment.video("c", "C", 20_000, 30_000, assetId);
		EditorSegment right = EditorSegment.video("a", "A", 0, 10_000, assetId);

		assertThat(validator.canShareSourceBoundary(left, right, assetId)).isFalse();
		assertThatThrownBy(() -> validator.assertShareableBoundaryNeighbors(left, right, assetId))
				.isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("share a source cut");
		assertThatThrownBy(() -> validator.assertSharedBoundary(left, right, 25_000, 30_000, assetId))
				.isInstanceOf(InvalidSegmentBoundaryException.class);
	}

	@Test
	void cropToOutputRangeShortensWholeProjectTo25Seconds() {
		List<EditorSegment> cropped = validator.cropToOutputRange(
				List.of(EditorSegment.video("a", "A", 0, 27_167)),
				0L,
				25_000L
		);

		assertThat(cropped).hasSize(1);
		assertThat(cropped.get(0).sourceStartMillis()).isZero();
		assertThat(cropped.get(0).sourceEndMillis()).isEqualTo(25_000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(cropped)).isEqualTo(25_000L);
	}

	@Test
	void cropToOutputRangeRejectsExpandingOutput() {
		assertThatThrownBy(() -> validator.cropToOutputRange(
				List.of(EditorSegment.video("a", "A", 0, 10_000)),
				0L,
				12_000L
		)).isInstanceOf(InvalidSegmentTrimException.class)
				.hasMessageContaining("exceed");
	}

	@Test
	void cropToOutputRangeTrimsAcrossTwoClips() {
		List<EditorSegment> cropped = validator.cropToOutputRange(
				List.of(
						EditorSegment.video("a", "A", 0, 20_000),
						EditorSegment.video("d", "D", 20_000, 27_167)
				),
				0L,
				25_000L
		);

		assertThat(cropped).hasSize(2);
		assertThat(cropped.get(0).sourceStartMillis()).isZero();
		assertThat(cropped.get(0).sourceEndMillis()).isEqualTo(20_000L);
		assertThat(cropped.get(1).sourceStartMillis()).isEqualTo(20_000L);
		assertThat(cropped.get(1).sourceEndMillis()).isEqualTo(25_000L);
		assertThat(EditorTimelineDurations.outputDurationMillis(cropped)).isEqualTo(25_000L);
	}

	@Test
	void cropToOutputRangeAccountsForPlaybackRate() {
		List<EditorSegment> cropped = validator.cropToOutputRange(
				List.of(EditorSegment.video("a", "A", 0, 20_000, null, 2.0d)),
				0L,
				5_000L
		);

		assertThat(cropped).hasSize(1);
		assertThat(cropped.get(0).sourceStartMillis()).isZero();
		assertThat(cropped.get(0).sourceEndMillis()).isEqualTo(10_000L);
		assertThat(cropped.get(0).playbackRate()).isEqualTo(2.0d);
		assertThat(EditorTimelineDurations.outputDurationMillis(cropped)).isEqualTo(5_000L);
	}

	@Test
	void rejectsAssetThatDoesNotBelongToProject() {
		EditorProperties properties = new EditorProperties();
		properties.setImageSegmentsEnabled(true);
		EditorSegmentValidator phase1b = new EditorSegmentValidator(properties);
		UUID projectAsset = UUID.randomUUID();
		UUID foreignAsset = UUID.randomUUID();

		assertThatThrownBy(() -> phase1b.normalize(List.of(
				seg("A", 0, 10_000),
				EditorSegment.image("img", "IMG", foreignAsset, 20_000)
		), 30_000, Set.of(projectAsset)))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("does not belong");

		assertThatThrownBy(() -> validator.normalize(List.of(
				EditorSegment.video("v", "A", 0, 4000, foreignAsset)
		), 4000, Set.of(projectAsset)))
				.isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("does not belong");
	}

	@Test
	void trimLastClipTo25Seconds() {
		EditorSegment last = EditorSegment.video("d", "D", 20_000, 27_167);
		EditorSegment trimmed = validator.assertTrim(last, 20_000, 25_000, false, true, 27_167);
		assertThat(trimmed.sourceStartMillis()).isEqualTo(20_000);
		assertThat(trimmed.sourceEndMillis()).isEqualTo(25_000);
		assertThat(trimmed.durationMillis()).isEqualTo(5_000);
	}

	@Test
	void trimRejectsExpandingRange() {
		EditorSegment last = EditorSegment.video("d", "D", 20_000, 25_000);
		assertThatThrownBy(() -> validator.assertTrim(last, 20_000, 27_000, false, true, 27_167))
				.isInstanceOf(InvalidSegmentTrimException.class)
				.hasMessageContaining("shorten");
	}

	private static EditorSegment seg(String label, long start, long end) {
		return new EditorSegment(UUID.randomUUID().toString(), label, start, end);
	}
}
