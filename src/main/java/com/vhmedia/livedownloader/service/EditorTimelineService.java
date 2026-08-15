package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.dto.request.ReorderEditorTimelineRequest;
import com.vhmedia.livedownloader.dto.request.ReplaceEditorSegmentVisualRequest;
import com.vhmedia.livedownloader.dto.request.ResizeEditorBoundaryRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorOutputRangeRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorSegmentSpeedRequest;
import com.vhmedia.livedownloader.dto.request.SplitEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.request.TrimEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.request.UpdateEditorSegmentsRequest;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.editor.EditorPlaybackRate;
import com.vhmedia.livedownloader.editor.EditorSegment;
import com.vhmedia.livedownloader.editor.EditorSegmentValidator;
import com.vhmedia.livedownloader.editor.EditorTimelineDurations;
import com.vhmedia.livedownloader.editor.SegmentMapper;
import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.AssetType;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.exception.EditorAssetNotFoundException;
import com.vhmedia.livedownloader.exception.EditorSegmentNotFoundException;
import com.vhmedia.livedownloader.exception.EditorSourceInvalidException;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import com.vhmedia.livedownloader.exception.InvalidEditorStateException;
import com.vhmedia.livedownloader.exception.InvalidSegmentBoundaryException;
import com.vhmedia.livedownloader.exception.InvalidSegmentTrimException;
import com.vhmedia.livedownloader.exception.InvalidSplitPositionException;
import com.vhmedia.livedownloader.exception.PlaybackRateNotSupportedForImageException;
import com.vhmedia.livedownloader.exception.SegmentsNotMergeableException;
import com.vhmedia.livedownloader.repository.VideoAssetRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Visual timeline mutations. Audio is never part of this model.
 * <p>
 * V1 has no undo/redo stack and no event sourcing. Undo-split is {@link #mergeNext}:
 * split keeps the left clip id; merge-next joins source-contiguous neighbors of the same
 * rate. Trim, boundary, and speed are explicit mutations of the current range/rate.
 * {@link #resetSegment} restores only state that is still stored (playback rate 1.0,
 * IMAGE → original VIDEO when the source slot is present). Trimmed source bounds are
 * not stored and are not guessed.
 */
@Slf4j
@Service
public class EditorTimelineService {

	private final EditorProperties editorProperties;
	private final VideoProjectRepository projectRepository;
	private final VideoAssetRepository assetRepository;
	private final VideoSegmentRepository segmentRepository;
	private final EditorSegmentValidator segmentValidator;
	private final VideoEditorService videoEditorService;

	public EditorTimelineService(
			EditorProperties editorProperties,
			VideoProjectRepository projectRepository,
			VideoAssetRepository assetRepository,
			VideoSegmentRepository segmentRepository,
			EditorSegmentValidator segmentValidator,
			VideoEditorService videoEditorService
	) {
		this.editorProperties = editorProperties;
		this.projectRepository = projectRepository;
		this.assetRepository = assetRepository;
		this.segmentRepository = segmentRepository;
		this.segmentValidator = segmentValidator;
		this.videoEditorService = videoEditorService;
	}

	@Transactional
	public EditorProjectResponse updateSegments(UUID projectId, UpdateEditorSegmentsRequest request) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<EditorSegment> incoming = new ArrayList<>();
		for (UpdateEditorSegmentsRequest.EditorSegmentInput input : request.getSegments()) {
			incoming.add(toDomainSegment(input, sourceAssetId));
		}
		List<EditorSegment> normalized = segmentValidator.normalize(
				incoming,
				project.getDurationMillis(),
				videoEditorService.assetIds(projectId)
		);
		videoEditorService.replaceSegments(projectId, sourceAssetId, normalized);
		videoEditorService.markTimelineDirty(project);
		projectRepository.save(project);
		log.info("Updated editor segments projectId={} count={}", projectId, normalized.size());
		return videoEditorService.toResponse(project);
	}

	@Transactional
	public EditorProjectResponse splitSegment(UUID projectId, UUID segmentId, SplitEditorSegmentRequest request) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getAtMillis() == null) {
			throw new InvalidSplitPositionException("atMillis is required");
		}
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		if (rows.size() >= editorProperties.getMaxSegments()) {
			throw new InvalidEditorSegmentsException("Too many segments (max " + editorProperties.getMaxSegments() + ")");
		}
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		VideoSegment target = rows.get(index);
		if (target.getType() != EditorSegmentType.VIDEO) {
			throw new InvalidSplitPositionException("Only VIDEO segments can be split");
		}
		long atMillis = request.getAtMillis();
		double rate = target.getPlaybackRate() <= 0.0d ? EditorPlaybackRate.DEFAULT : target.getPlaybackRate();
		segmentValidator.assertSplitPoint(
				target.getSourceStartMillis(),
				atMillis,
				target.getSourceEndMillis(),
				rate
		);

		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		UUID leftAssetId = target.getAssetId() != null ? target.getAssetId() : sourceAssetId;
		List<EditorSegment> next = new ArrayList<>(rows.size() + 1);
		for (int i = 0; i < rows.size(); i++) {
			if (i != index) {
				next.add(SegmentMapper.toDomain(rows.get(i)));
				continue;
			}
			long start = target.getSourceStartMillis();
			long end = target.getSourceEndMillis();
			next.add(EditorSegment.video(target.getId().toString(), target.getLabel(), start, atMillis, leftAssetId, rate));
			next.add(EditorSegment.video(UUID.randomUUID().toString(), null, atMillis, end, leftAssetId, rate));
		}
		List<EditorSegment> normalized = segmentValidator.normalize(
				next,
				project.getDurationMillis(),
				videoEditorService.assetIds(projectId)
		);
		videoEditorService.replaceSegments(projectId, sourceAssetId, normalized);
		videoEditorService.markTimelineDirty(project);
		projectRepository.save(project);
		log.info("Split editor segment projectId={} segmentId={} atMillis={}", projectId, segmentId, atMillis);
		return videoEditorService.toResponse(project);
	}

	@Transactional
	public EditorProjectResponse reorderTimeline(UUID projectId, ReorderEditorTimelineRequest request) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		List<UUID> segmentIds = request.getSegmentIds();
		if (segmentIds == null || segmentIds.isEmpty()) {
			throw new InvalidEditorSegmentsException("segmentIds must not be empty");
		}
		if (new HashSet<>(segmentIds).size() != segmentIds.size()) {
			throw new InvalidEditorSegmentsException("Duplicate segment id");
		}
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		if (rows.isEmpty()) {
			throw new InvalidEditorSegmentsException("Segments must be defined before reorder");
		}
		if (segmentIds.size() != rows.size()) {
			throw new InvalidEditorSegmentsException("segmentIds must include every project segment exactly once");
		}
		Map<UUID, VideoSegment> byId = new LinkedHashMap<>();
		for (VideoSegment row : rows) {
			byId.put(row.getId(), row);
		}
		if (!byId.keySet().equals(Set.copyOf(segmentIds))) {
			throw new InvalidEditorSegmentsException("segmentIds must match the project timeline");
		}
		List<EditorSegment> visual = new ArrayList<>(segmentIds.size());
		for (UUID id : segmentIds) {
			visual.add(SegmentMapper.toDomain(byId.get(id)));
		}
		List<EditorSegment> normalized = segmentValidator.normalize(
				visual,
				project.getDurationMillis(),
				videoEditorService.assetIds(projectId)
		);
		videoEditorService.replaceSegments(projectId, VideoEditorService.requireSourceAssetId(project), normalized);
		videoEditorService.markTimelineDirty(project);
		projectRepository.save(project);
		log.info("Reordered editor timeline projectId={} count={}", projectId, normalized.size());
		return videoEditorService.toResponse(project);
	}

	/**
	 * Undo-split: join {@code segmentId} with the next clip on the visual timeline.
	 * Metadata only — no FFmpeg, no new files.
	 */
	@Transactional
	public EditorProjectResponse mergeNext(UUID projectId, UUID segmentId) {
		return mergeNext(projectId, segmentId, null);
	}

	@Transactional
	public EditorProjectResponse mergeNext(UUID projectId, UUID segmentId, Long timelineVersion) {
		VideoProject project = lockTimeline(projectId, timelineVersion);
		requireKnownDuration(project);
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		if (index + 1 >= rows.size()) {
			throw new SegmentsNotMergeableException("There is no next clip to merge.");
		}
		EditorSegment left = SegmentMapper.toDomain(rows.get(index));
		EditorSegment right = SegmentMapper.toDomain(rows.get(index + 1));
		segmentValidator.assertMergeableNeighbors(left, right, sourceAssetId);

		UUID mergedAssetId = left.assetId() != null ? left.assetId() : sourceAssetId;
		long mergedStart = left.sourceStartMillis();
		long mergedEnd = right.sourceEndMillis();
		List<EditorSegment> next = new ArrayList<>(rows.size() - 1);
		for (int i = 0; i < rows.size(); i++) {
			if (i == index) {
				next.add(EditorSegment.video(
						left.id(),
						left.label(),
						mergedStart,
						mergedEnd,
						mergedAssetId,
						left.playbackRate()
				));
				continue;
			}
			if (i == index + 1) {
				continue;
			}
			next.add(SegmentMapper.toDomain(rows.get(i)));
		}
		persistNormalized(project, sourceAssetId, next);
		log.info(
				"Merged editor segments projectId={} leftId={} rightId={} range={}..{}",
				projectId,
				left.id(),
				right.id(),
				mergedStart,
				mergedEnd
		);
		return videoEditorService.toResponse(project);
	}

	/**
	 * Move the shared source cut between {@code leftSegmentId} and the next timeline neighbor.
	 * No gap, no overlap. Metadata only.
	 */
	@Transactional
	public EditorProjectResponse resizeBoundary(
			UUID projectId,
			UUID leftSegmentId,
			ResizeEditorBoundaryRequest request
	) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getBoundaryMillis() == null) {
			throw new InvalidSegmentBoundaryException("boundaryMillis is required");
		}
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, leftSegmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + leftSegmentId);
		}
		if (index + 1 >= rows.size()) {
			throw new InvalidSegmentBoundaryException("There is no next clip to move this cut against.");
		}
		EditorSegment left = SegmentMapper.toDomain(rows.get(index));
		EditorSegment right = SegmentMapper.toDomain(rows.get(index + 1));
		long boundary = segmentValidator.assertSharedBoundary(
				left,
				right,
				request.getBoundaryMillis(),
				project.getDurationMillis(),
				sourceAssetId
		);

		UUID leftAsset = left.assetId() != null ? left.assetId() : sourceAssetId;
		UUID rightAsset = right.assetId() != null ? right.assetId() : sourceAssetId;
		List<EditorSegment> next = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			if (i == index) {
				next.add(EditorSegment.video(
						left.id(),
						left.label(),
						left.sourceStartMillis(),
						boundary,
						leftAsset,
						left.playbackRate()
				));
				continue;
			}
			if (i == index + 1) {
				next.add(EditorSegment.video(
						right.id(),
						right.label(),
						boundary,
						right.sourceEndMillis(),
						rightAsset,
						right.playbackRate()
				));
				continue;
			}
			next.add(SegmentMapper.toDomain(rows.get(i)));
		}
		persistNormalized(project, sourceAssetId, next);
		log.info(
				"Moved editor boundary projectId={} leftId={} rightId={} boundaryMillis={}",
				projectId,
				left.id(),
				right.id(),
				boundary
		);
		return videoEditorService.toResponse(project);
	}

	/**
	 * Canonical project-level trim: crop the current visual output to {@code [start, end)}.
	 * Example: 27.167s → 25.000s is {@code startMillis=0, endMillis=25000}.
	 * Segment {@code PUT .../trim} remains for first/last clip source handles; both write the same segment model.
	 */
	@Transactional
	public EditorProjectResponse setOutputRange(UUID projectId, SetEditorOutputRangeRequest request) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getStartMillis() == null || request.getEndMillis() == null) {
			throw new InvalidSegmentTrimException("startMillis and endMillis are required");
		}
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		List<EditorSegment> visual = new ArrayList<>(rows.size());
		for (VideoSegment row : rows) {
			visual.add(SegmentMapper.toDomain(row));
		}
		List<EditorSegment> cropped = segmentValidator.cropToOutputRange(
				visual,
				request.getStartMillis(),
				request.getEndMillis()
		);
		persistNormalized(project, sourceAssetId, cropped);
		log.info(
				"Set editor output range projectId={} startMs={} endMs={} outputDurationMs={}",
				projectId,
				request.getStartMillis(),
				request.getEndMillis(),
				EditorTimelineDurations.outputDurationMillis(cropped)
		);
		return videoEditorService.toResponse(project);
	}

	/**
	 * Trim the left edge of the first clip and/or the right edge of the last clip.
	 * Metadata only. Output duration becomes the sum of visual clips; audio is original[0..output].
	 */
	@Transactional
	public EditorProjectResponse trimSegment(UUID projectId, UUID segmentId, TrimEditorSegmentRequest request) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getSourceStartMillis() == null || request.getSourceEndMillis() == null) {
			throw new InvalidSegmentTrimException("sourceStartMillis and sourceEndMillis are required");
		}
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		EditorSegment current = SegmentMapper.toDomain(rows.get(index));
		EditorSegment trimmed = segmentValidator.assertTrim(
				current,
				request.getSourceStartMillis(),
				request.getSourceEndMillis(),
				index == 0,
				index == rows.size() - 1,
				project.getDurationMillis()
		);
		List<EditorSegment> next = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			next.add(i == index ? trimmed : SegmentMapper.toDomain(rows.get(i)));
		}
		persistNormalized(project, sourceAssetId, next);
		log.info(
				"Trimmed editor segment projectId={} segmentId={} sourceRange={}..{} outputDurationMs={}",
				projectId,
				segmentId,
				trimmed.sourceStartMillis(),
				trimmed.sourceEndMillis(),
				EditorTimelineDurations.outputDurationMillis(next)
		);
		return videoEditorService.toResponse(project);
	}

	/**
	 * Visual playback speed only. Audio stays {@code original[0..outputDuration]}.
	 * Slow-motion that would exceed the original audio is rejected.
	 */
	@Transactional
	public EditorProjectResponse setSegmentSpeed(
			UUID projectId,
			UUID segmentId,
			SetEditorSegmentSpeedRequest request
	) {
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getPlaybackRate() == null) {
			throw new InvalidEditorSegmentsException("playbackRate is required");
		}
		double rate = EditorPlaybackRate.canonicalize(request.getPlaybackRate());
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		EditorSegment current = SegmentMapper.toDomain(rows.get(index));
		if (current.isImage()) {
			throw new PlaybackRateNotSupportedForImageException();
		}
		if (!current.isVideo()) {
			throw new InvalidEditorSegmentsException("Only VIDEO clips can change playback speed");
		}
		EditorSegment sped = current.withPlaybackRate(rate);
		segmentValidator.assertMinDuration(sped.durationMillis());
		List<EditorSegment> next = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			next.add(i == index ? sped : SegmentMapper.toDomain(rows.get(i)));
		}
		persistNormalized(project, sourceAssetId, next);
		log.info(
				"Set editor playback rate projectId={} segmentId={} playbackRate={} visualDurationMs={} outputDurationMs={}",
				projectId,
				segmentId,
				rate,
				sped.durationMillis(),
				EditorTimelineDurations.outputDurationMillis(next)
		);
		return videoEditorService.toResponse(project);
	}

	/**
	 * Restore stored clip state only:
	 * <ul>
	 *   <li>VIDEO {@code playbackRate → 1.0} (current source range is kept — trim is not undone).</li>
	 *   <li>IMAGE with a stored source slot → original VIDEO on the project source asset at 1.0x.</li>
	 * </ul>
	 * Does not invent original trim bounds. Undo-split remains {@link #mergeNext}.
	 */
	@Transactional
	public EditorProjectResponse resetSegment(UUID projectId, UUID segmentId) {
		return resetSegment(projectId, segmentId, null);
	}

	@Transactional
	public EditorProjectResponse resetSegment(UUID projectId, UUID segmentId, Long timelineVersion) {
		VideoProject project = lockTimeline(projectId, timelineVersion);
		requireKnownDuration(project);
		UUID sourceAssetId = VideoEditorService.requireSourceAssetId(project);
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		EditorSegment current = SegmentMapper.toDomain(rows.get(index));
		EditorSegment restored;
		if (current.isImage()) {
			if (!current.hasSourceSlot()) {
				throw new InvalidEditorSegmentsException(
						"This IMAGE clip has no stored source slot, so the original video cannot be restored"
				);
			}
			restored = EditorSegment.video(
					current.id(),
					current.label(),
					current.sourceStartMillis(),
					current.sourceEndMillis(),
					sourceAssetId,
					EditorPlaybackRate.DEFAULT
			);
		} else {
			restored = current.withPlaybackRate(EditorPlaybackRate.DEFAULT);
		}
		List<EditorSegment> next = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			next.add(i == index ? restored : SegmentMapper.toDomain(rows.get(i)));
		}
		persistNormalized(project, sourceAssetId, next);
		log.info(
				"Reset editor segment projectId={} segmentId={} type={} playbackRate=1.0 sourceRange={}..{}",
				projectId,
				segmentId,
				restored.type(),
				restored.sourceStartMillis(),
				restored.sourceEndMillis()
		);
		return videoEditorService.toResponse(project);
	}

	@Transactional
	public EditorProjectResponse replaceSegmentVisual(
			UUID projectId,
			UUID segmentId,
			ReplaceEditorSegmentVisualRequest request
	) {
		if (!editorProperties.isImageSegmentsEnabled()) {
			throw new InvalidEditorStateException(
					"IMAGE assets are not enabled (set EDITOR_IMAGE_SEGMENTS_ENABLED=true for Phase 1B)"
			);
		}
		VideoProject project = lockTimeline(projectId, request.getTimelineVersion());
		requireKnownDuration(project);
		if (request.getAssetId() == null) {
			throw new InvalidEditorSegmentsException("assetId is required");
		}
		VideoAsset image = assetRepository.findByIdAndProjectId(request.getAssetId(), projectId)
				.orElseThrow(() -> new EditorAssetNotFoundException("Editor asset not found: " + request.getAssetId()));
		if (image.getType() != AssetType.IMAGE || image.isPrimarySource()) {
			throw new InvalidEditorSegmentsException("assetId must refer to an IMAGE asset on this project");
		}
		List<VideoSegment> rows = segmentRepository.findByProjectIdOrderByPositionAsc(projectId);
		int index = indexOfSegment(rows, segmentId);
		if (index < 0) {
			throw new EditorSegmentNotFoundException("Editor segment not found: " + segmentId);
		}
		VideoSegment target = rows.get(index);
		EditorSegment current = SegmentMapper.toDomain(target);
		if (current.isVideo() && (current.sourceStartMillis() == null || current.sourceEndMillis() == null
				|| current.sourceEndMillis() <= current.sourceStartMillis())) {
			throw new InvalidEditorSegmentsException("VIDEO segment is missing a source range");
		}
		long duration = current.durationMillis();
		Long start = target.getSourceStartMillis();
		Long end = target.getSourceEndMillis();
		List<EditorSegment> next = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			if (i != index) {
				next.add(SegmentMapper.toDomain(rows.get(i)));
				continue;
			}
			next.add(EditorSegment.image(
					target.getId().toString(),
					target.getLabel(),
					image.getId(),
					duration,
					start,
					end
			));
		}
		List<EditorSegment> normalized = segmentValidator.normalize(
				next,
				project.getDurationMillis(),
				videoEditorService.assetIds(projectId)
		);
		videoEditorService.replaceSegments(projectId, VideoEditorService.requireSourceAssetId(project), normalized);
		videoEditorService.markTimelineDirty(project);
		projectRepository.save(project);
		log.info(
				"Replaced editor segment visual projectId={} segmentId={} assetId={} durationMs={}",
				projectId,
				segmentId,
				image.getId(),
				duration
		);
		return videoEditorService.toResponse(project);
	}

	private List<EditorSegment> persistNormalized(
			VideoProject project,
			UUID sourceAssetId,
			List<EditorSegment> visual
	) {
		List<EditorSegment> normalized = segmentValidator.normalize(
				visual,
				project.getDurationMillis(),
				videoEditorService.assetIds(project.getId())
		);
		EditorTimelineDurations.assertFitsLockedAudio(
				project.isHasAudio(),
				project.getDurationMillis(),
				normalized
		);
		videoEditorService.replaceSegments(project.getId(), sourceAssetId, normalized);
		videoEditorService.markTimelineDirty(project);
		projectRepository.save(project);
		return normalized;
	}

	private VideoProject lockTimeline(UUID projectId, Long expectedTimelineVersion) {
		VideoProject project = videoEditorService.lockMutableProject(projectId);
		videoEditorService.requireMatchingTimelineVersion(project, expectedTimelineVersion);
		return project;
	}

	private static void requireKnownDuration(VideoProject project) {
		if (project.getDurationMillis() == null || project.getDurationMillis() <= 0) {
			throw new EditorSourceInvalidException("Source duration is unknown");
		}
	}

	private static EditorSegment toDomainSegment(UpdateEditorSegmentsRequest.EditorSegmentInput input, UUID sourceAssetId) {
		EditorSegmentType type = input.resolvedType();
		if (type == EditorSegmentType.IMAGE) {
			if (input.getAssetId() == null) {
				throw new InvalidEditorSegmentsException("IMAGE segments require assetId");
			}
			if (input.getDurationMillis() == null) {
				throw new InvalidEditorSegmentsException(
						"IMAGE segments require durationMillis equal to the replaced video slot"
				);
			}
			return EditorSegment.image(
					UUID.randomUUID().toString(),
					input.getLabel(),
					input.getAssetId(),
					input.getDurationMillis(),
					input.getSourceStartMillis(),
					input.getSourceEndMillis()
			);
		}
		UUID assetId = input.getAssetId() != null ? input.getAssetId() : sourceAssetId;
		return EditorSegment.video(
				UUID.randomUUID().toString(),
				input.getLabel(),
				input.resolvedSourceStartMillis(),
				input.resolvedSourceEndMillis(),
				assetId,
				EditorPlaybackRate.canonicalize(input.getPlaybackRate())
		);
	}

	private static int indexOfSegment(List<VideoSegment> rows, UUID segmentId) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).getId().equals(segmentId)) {
				return i;
			}
		}
		return -1;
	}
}
