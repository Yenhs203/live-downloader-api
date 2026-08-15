package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.EditorProperties;
import com.vhmedia.livedownloader.dto.request.ReorderEditorTimelineRequest;
import com.vhmedia.livedownloader.dto.request.ReplaceEditorSegmentVisualRequest;
import com.vhmedia.livedownloader.dto.request.ResizeEditorBoundaryRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorOutputRangeRequest;
import com.vhmedia.livedownloader.dto.request.SetEditorSegmentSpeedRequest;
import com.vhmedia.livedownloader.dto.request.SplitEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.request.TrimEditorSegmentRequest;
import com.vhmedia.livedownloader.dto.response.EditorProjectResponse;
import com.vhmedia.livedownloader.editor.EditorExportPlanner;
import com.vhmedia.livedownloader.editor.EditorSegmentValidator;
import com.vhmedia.livedownloader.entity.VideoAsset;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.AssetType;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import com.vhmedia.livedownloader.exception.EditorExportNotFoundException;
import com.vhmedia.livedownloader.exception.EditorSegmentNotFoundException;
import com.vhmedia.livedownloader.exception.ExportAlreadyRunningException;
import com.vhmedia.livedownloader.exception.ExportNotReadyException;
import com.vhmedia.livedownloader.exception.InvalidEditorSegmentsException;
import com.vhmedia.livedownloader.exception.InvalidSegmentBoundaryException;
import com.vhmedia.livedownloader.exception.InvalidSegmentTrimException;
import com.vhmedia.livedownloader.exception.OutputDurationExceedsAudioException;
import com.vhmedia.livedownloader.exception.PlaybackRateNotSupportedForImageException;
import com.vhmedia.livedownloader.exception.SegmentTooShortException;
import com.vhmedia.livedownloader.exception.SegmentsNotMergeableException;
import com.vhmedia.livedownloader.exception.TimelineConflictException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.repository.VideoAssetRepository;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import com.vhmedia.livedownloader.util.EditorPathResolver;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoEditorServiceTest {

	@Mock
	private VideoProjectRepository projectRepository;
	@Mock
	private VideoAssetRepository assetRepository;
	@Mock
	private VideoSegmentRepository segmentRepository;
	@Mock
	private VideoExportJobRepository exportJobRepository;
	@Mock
	private LiveDownloadJobRepository recordingRepository;
	@Mock
	private RecordingPathResolver recordingPathResolver;
	@Mock
	private EditorPathResolver editorPathResolver;
	@Mock
	private FfprobeService ffprobeService;
	@Mock
	private EditorAssetService editorAssetService;

	private VideoEditorService service;
	private EditorTimelineService timelineService;

	@BeforeEach
	void setUp() {
		EditorProperties properties = new EditorProperties();
		properties.setMaxSegments(50);
		properties.setMinSegmentMillis(100);
		properties.setCoverageEpsilonMillis(50);
		properties.setImageSegmentsEnabled(true);
		service = new VideoEditorService(
				properties,
				projectRepository,
				assetRepository,
				segmentRepository,
				exportJobRepository,
				recordingRepository,
				recordingPathResolver,
				editorPathResolver,
				ffprobeService,
				new EditorSegmentValidator(properties),
				new EditorExportPlanner(),
				editorAssetService
		);
		timelineService = new EditorTimelineService(
				properties,
				projectRepository,
				assetRepository,
				segmentRepository,
				new EditorSegmentValidator(properties),
				service
		);
	}

	@Test
	void splitKeepsLeftIdAndInsertsRightPiece() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(videoSegment(leftId, projectId, sourceAssetId, 0, 0, 30_000)));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.splitSegment(
				projectId,
				leftId,
				SplitEditorSegmentRequest.builder().atMillis(12_000L).build()
		);

		assertThat(response.getSegments()).hasSize(2);
		assertThat(response.getSegments().get(0).getId()).isEqualTo(leftId.toString());
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(12_000L);
		assertThat(response.getSegments().get(0).getDurationMillis()).isEqualTo(12_000L);
		assertThat(response.getSegments().get(0).getPosition()).isZero();
		assertThat(response.getSegments().get(0).isCanMergeNext()).isTrue();
		assertThat(response.getSegments().get(1).getId()).isNotEqualTo(leftId.toString());
		assertThat(response.getSegments().get(1).getSourceStartMillis()).isEqualTo(12_000L);
		assertThat(response.getSegments().get(1).getSourceEndMillis()).isEqualTo(30_000L);
		assertThat(response.getSegments().get(1).getPosition()).isEqualTo(1);
		assertThat(response.getVisualDurationMillis()).isEqualTo(30_000L);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<VideoSegment>> captor = ArgumentCaptor.forClass(List.class);
		verify(segmentRepository).deleteByProjectId(projectId);
		verify(segmentRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).extracting(VideoSegment::getPosition).containsExactly(0, 1);
		assertThat(captor.getValue().get(0).getId()).isEqualTo(leftId);
	}

	@Test
	void staleTimelineVersionIsRejectedBeforeMutating() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		project.setTimelineVersion(3L);
		stubMutableProject(project);

		assertThatThrownBy(() -> timelineService.splitSegment(
				projectId,
				leftId,
				SplitEditorSegmentRequest.builder().atMillis(12_000L).timelineVersion(2L).build()
		)).isInstanceOf(TimelineConflictException.class)
				.hasMessageContaining("Reload");
		verify(segmentRepository, never()).deleteByProjectId(any());
	}

	@Test
	void omittedTimelineVersionAllowsMutation() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		project.setTimelineVersion(5L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(videoSegment(leftId, projectId, sourceAssetId, 0, 0, 10_000)));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.splitSegment(
				projectId,
				leftId,
				SplitEditorSegmentRequest.builder().atMillis(4_000L).build()
		);

		assertThat(response.getSegments()).hasSize(2);
		assertThat(response.getSegments().get(0).getSourceDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getSegments().get(0).getVisualDurationMillis()).isEqualTo(4_000L);
	}

	@Test
	void matchingTimelineVersionAllowsMutation() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		project.setTimelineVersion(1L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(videoSegment(leftId, projectId, sourceAssetId, 0, 0, 10_000)));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.splitSegment(
				projectId,
				leftId,
				SplitEditorSegmentRequest.builder().atMillis(4_000L).timelineVersion(1L).build()
		);

		assertThat(response.getSegments()).hasSize(2);
	}

	@Test
	void splitRejectsUnknownSegment() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId))
				.thenReturn(List.of(videoSegment(UUID.randomUUID(), projectId, sourceAssetId, 0, 0, 30_000)));

		assertThatThrownBy(() -> timelineService.splitSegment(
				projectId,
				UUID.randomUUID(),
				SplitEditorSegmentRequest.builder().atMillis(12_000L).build()
		)).isInstanceOf(EditorSegmentNotFoundException.class);
		verify(segmentRepository, never()).deleteByProjectId(any());
	}

	@Test
	void splitRejectsPointTooCloseToBoundary() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId))
				.thenReturn(List.of(videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 30_000)));

		assertThatThrownBy(() -> timelineService.splitSegment(
				projectId,
				segmentId,
				SplitEditorSegmentRequest.builder().atMillis(50L).build()
		)).isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
	}

	@Test
	void splitRejectsImageSegment() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		VideoSegment image = VideoSegment.builder()
				.id(segmentId)
				.projectId(projectId)
				.assetId(sourceAssetId)
				.type(EditorSegmentType.IMAGE)
				.label("IMG")
				.durationMillis(30_000)
				.position(0)
				.build();
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(image));

		assertThatThrownBy(() -> timelineService.splitSegment(
				projectId,
				segmentId,
				SplitEditorSegmentRequest.builder().atMillis(12_000L).build()
		)).isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("VIDEO");
	}

	@Test
	void splitThenMergeNextRestoresOriginalRange() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(leftId, projectId, sourceAssetId, 0, 0, 10_000)
		));
		stubTimelineStore(projectId, stored);

		timelineService.splitSegment(
				projectId,
				leftId,
				SplitEditorSegmentRequest.builder().atMillis(4_000L).build()
		);
		assertThat(stored).hasSize(2);

		EditorProjectResponse merged = timelineService.mergeNext(projectId, leftId);

		assertThat(merged.getSegments()).hasSize(1);
		assertThat(merged.getSegments().get(0).getId()).isEqualTo(leftId.toString());
		assertThat(merged.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(merged.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(0).getDurationMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(0).getPosition()).isZero();
		assertThat(merged.getSegments().get(0).isCanMergeNext()).isFalse();
		assertThat(merged.getVisualDurationMillis()).isEqualTo(10_000L);
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).getId()).isEqualTo(leftId);
	}

	@Test
	void mergeNextJoinsZeroToFiveWithFiveToTen() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID leftId = UUID.randomUUID();
		UUID rightId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(leftId, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(rightId, projectId, sourceAssetId, 1, 5_000, 10_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse merged = timelineService.mergeNext(projectId, leftId);

		assertThat(merged.getSegments()).hasSize(1);
		assertThat(merged.getSegments().get(0).getId()).isEqualTo(leftId.toString());
		assertThat(merged.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(merged.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(0).getDurationMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(0).getPosition()).isZero();
		assertThat(merged.getOutputDurationMillis()).isEqualTo(10_000L);
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).getId()).isEqualTo(leftId);
		assertThat(stored.get(0).getSourceStartMillis()).isZero();
		assertThat(stored.get(0).getSourceEndMillis()).isEqualTo(10_000L);
	}

	@Test
	void mergeNextRejectsZeroToFivePlusTenToFifteen() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 15_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(c, projectId, sourceAssetId, 1, 10_000, 15_000)
		));

		assertThatThrownBy(() -> timelineService.mergeNext(projectId, a))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void mergeNextRejectsDifferentAssets() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID otherAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, otherAssetId, 1, 5_000, 10_000)
		));

		assertThatThrownBy(() -> timelineService.mergeNext(projectId, a))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("different sources");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void mergeNextReindexesPositions() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 15_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000),
				videoSegment(c, projectId, sourceAssetId, 2, 10_000, 15_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse merged = timelineService.mergeNext(projectId, a);

		assertThat(merged.getSegments()).hasSize(2);
		assertThat(merged.getSegments().get(0).getId()).isEqualTo(a.toString());
		assertThat(merged.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(merged.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(0).getPosition()).isZero();
		assertThat(merged.getSegments().get(1).getId()).isEqualTo(c.toString());
		assertThat(merged.getSegments().get(1).getSourceStartMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(1).getSourceEndMillis()).isEqualTo(15_000L);
		assertThat(merged.getSegments().get(1).getPosition()).isEqualTo(1);
		assertThat(stored).extracting(VideoSegment::getPosition).containsExactly(0, 1);
		assertThat(stored).extracting(VideoSegment::getId).containsExactly(a, c);
	}

	@Test
	void mergeNextRejectsNonContiguousNeighbors() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 25_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(c, projectId, sourceAssetId, 1, 20_000, 25_000)
		));

		assertThatThrownBy(() -> timelineService.mergeNext(projectId, a))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void mergeNextRejectsReorderedNeighborsThatDoNotMeetOnSource() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(b, projectId, sourceAssetId, 0, 5_000, 10_000),
				videoSegment(a, projectId, sourceAssetId, 1, 0, 5_000)
		));

		assertThatThrownBy(() -> timelineService.mergeNext(projectId, b))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
	}

	@Test
	void mergeNextRejectsLastSegment() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID only = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId))
				.thenReturn(List.of(videoSegment(only, projectId, sourceAssetId, 0, 0, 10_000)));

		assertThatThrownBy(() -> timelineService.mergeNext(projectId, only))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("no next clip");
	}

	@Test
	void resizeBoundaryMovesSharedCut() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.resizeBoundary(
				projectId,
				a,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(6_000L).build()
		);

		assertThat(response.getSegments()).hasSize(2);
		assertThat(response.getSegments().get(0).getId()).isEqualTo(a.toString());
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(6_000L);
		assertThat(response.getSegments().get(0).getDurationMillis()).isEqualTo(6_000L);
		assertThat(response.getSegments().get(1).getId()).isEqualTo(b.toString());
		assertThat(response.getSegments().get(1).getSourceStartMillis()).isEqualTo(6_000L);
		assertThat(response.getSegments().get(1).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(1).getDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getVisualDurationMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(0).isCanResizeRightBoundary()).isTrue();
		assertThat(response.getSegments().get(0).isCanMergeNext()).isTrue();
		assertThat(response.getSegments().get(1).isCanResizeLeftBoundary()).isTrue();
		assertThat(response.getSegments().get(1).isCanResizeRightBoundary()).isFalse();
		assertThat(response.getSegments().get(0).getSourceEndMillis())
				.isEqualTo(response.getSegments().get(1).getSourceStartMillis());
		assertThat(response.getSegments().get(0).getSourceEndMillis())
				.isLessThanOrEqualTo(response.getSegments().get(1).getSourceStartMillis());
		long covered = response.getSegments().get(0).getDurationMillis()
				+ response.getSegments().get(1).getDurationMillis();
		assertThat(covered).isEqualTo(10_000L);
	}

	@Test
	void resizeBoundaryRejectsZero() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000)
		));

		assertThatThrownBy(() -> timelineService.resizeBoundary(
				projectId,
				a,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(0L).build()
		)).isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("strictly between");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void resizeBoundaryRejectsTen() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000)
		));

		assertThatThrownBy(() -> timelineService.resizeBoundary(
				projectId,
				a,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(10_000L).build()
		)).isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("strictly between");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void resizeBoundaryRejectsPieceShorterThanMinimum() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000)
		));

		assertThatThrownBy(() -> timelineService.resizeBoundary(
				projectId,
				a,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(50L).build()
		)).isInstanceOf(SegmentTooShortException.class)
				.hasMessageContaining("at least");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void resizeBoundaryRejectsNonContiguousNeighbors() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 25_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(c, projectId, sourceAssetId, 1, 20_000, 25_000)
		));

		assertThatThrownBy(() -> timelineService.resizeBoundary(
				projectId,
				a,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(6_000L).build()
		)).isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("share a source cut");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void resizeBoundaryAfterReorderRejectsNonContiguousVisualNeighbors() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 10_000),
				videoSegment(b, projectId, sourceAssetId, 1, 10_000, 20_000),
				videoSegment(c, projectId, sourceAssetId, 2, 20_000, 30_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(c, a, b)).build()
		);
		assertThat(response.getSegments()).extracting(seg -> seg.getId())
				.containsExactly(c.toString(), a.toString(), b.toString());
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isEqualTo(20_000L);
		assertThat(response.getSegments().get(1).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).isCanMergeNext()).isFalse();
		assertThat(response.getSegments().get(0).isCanResizeRightBoundary()).isFalse();
		assertThat(response.getSegments().get(1).isCanResizeLeftBoundary()).isFalse();
		assertThat(response.getSegments().get(1).isCanResizeRightBoundary()).isTrue();
		assertThat(response.getSegments().get(2).isCanResizeLeftBoundary()).isTrue();

		assertThatThrownBy(() -> timelineService.resizeBoundary(
				projectId,
				c,
				ResizeEditorBoundaryRequest.builder().boundaryMillis(25_000L).build()
		)).isInstanceOf(InvalidSegmentBoundaryException.class)
				.hasMessageContaining("share a source cut");
		assertThat(stored).hasSize(3);
		assertThat(stored.get(0).getSourceStartMillis()).isEqualTo(20_000L);
		assertThat(stored.get(1).getSourceStartMillis()).isZero();
	}

	@Test
	void trimLastClipShortensOutputAndKeepsSourceDuration() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID d = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 27_167L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 20_000),
				videoSegment(d, projectId, sourceAssetId, 1, 20_000, 27_167)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.trimSegment(
				projectId,
				d,
				TrimEditorSegmentRequest.builder().sourceStartMillis(20_000L).sourceEndMillis(25_000L).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(27_167L);
		assertThat(response.getDurationMillis()).isEqualTo(27_167L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getVisualDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getSegments()).hasSize(2);
		assertThat(response.getSegments().get(1).getSourceStartMillis()).isEqualTo(20_000L);
		assertThat(response.getSegments().get(1).getSourceEndMillis()).isEqualTo(25_000L);
		assertThat(response.getSegments().get(1).getDurationMillis()).isEqualTo(5_000L);
		assertThat(response.getSegments().get(1).getSourceDurationMillis()).isEqualTo(5_000L);
		assertThat(response.getSegments().get(1).getVisualDurationMillis()).isEqualTo(5_000L);
		assertThat(response.getTimelineVersion()).isZero();
	}

	@Test
	void trimSingleClipTo25Seconds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 27_167L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 27_167)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.trimSegment(
				projectId,
				a,
				TrimEditorSegmentRequest.builder().sourceStartMillis(0L).sourceEndMillis(25_000L).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(27_167L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(25_000L);
	}

	@Test
	void setOutputRangeCropsWholeProjectTo25Seconds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 27_167L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 27_167)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.setOutputRange(
				projectId,
				SetEditorOutputRangeRequest.builder().startMillis(0L).endMillis(25_000L).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(27_167L);
		assertThat(response.getDurationMillis()).isEqualTo(27_167L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getVisualDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(25_000L);
	}

	@Test
	void setOutputRangeRejectsExpandingOutput() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 27_167L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 27_167)
		));

		assertThatThrownBy(() -> timelineService.setOutputRange(
				projectId,
				SetEditorOutputRangeRequest.builder().startMillis(0L).endMillis(30_000L).build()
		)).isInstanceOf(InvalidSegmentTrimException.class)
				.hasMessageContaining("exceed");
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void trimRejectsMiddleClipRightEdge() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 15_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(b, projectId, sourceAssetId, 1, 5_000, 10_000),
				videoSegment(c, projectId, sourceAssetId, 2, 10_000, 15_000)
		));

		assertThatThrownBy(() -> timelineService.trimSegment(
				projectId,
				b,
				TrimEditorSegmentRequest.builder().sourceStartMillis(5_000L).sourceEndMillis(8_000L).build()
		)).isInstanceOf(InvalidSegmentTrimException.class)
				.hasMessageContaining("last clip");
	}

	@Test
	void trimRejectsTooShort() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId))
				.thenReturn(List.of(videoSegment(a, projectId, sourceAssetId, 0, 0, 10_000)));

		assertThatThrownBy(() -> timelineService.trimSegment(
				projectId,
				a,
				TrimEditorSegmentRequest.builder().sourceStartMillis(0L).sourceEndMillis(50L).build()
		)).isInstanceOf(SegmentTooShortException.class);
	}

	@Test
	void setSegmentSpeedPersistsRateAndShortensOutput() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 10_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(2.0d).build()
		);

		assertThat(response.getOutputDurationMillis()).isEqualTo(5_000L);
		assertThat(response.getSourceDurationMillis()).isEqualTo(10_000L);
		assertThat(response.getDurationMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(2.0d);
		assertThat(response.getSegments().get(0).getSourceDurationMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(0).getVisualDurationMillis()).isEqualTo(5_000L);
		assertThat(response.getSegments().get(0).getDurationMillis()).isEqualTo(5_000L);
		assertThat(stored.get(0).getPlaybackRate()).isEqualTo(2.0d);
	}

	@Test
	void fourSecondClipAtOneXStaysFourSeconds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 4_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(1.0d).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(1.0d);
		assertThat(response.getSegments().get(0).getVisualDurationMillis()).isEqualTo(4_000L);
	}

	@Test
	void fourSecondClipAtTwoXBecomesTwoSeconds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 4_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(2.0d).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(2_000L);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(4_000L);
		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(2.0d);
		assertThat(response.getSegments().get(0).getVisualDurationMillis()).isEqualTo(2_000L);
	}

	@Test
	void fourSecondClipAtHalfXBecomesEightSecondsWhenAudioAllows() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 8_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 4_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(0.5d).build()
		);

		assertThat(response.getSourceDurationMillis()).isEqualTo(8_000L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(8_000L);
		assertThat(response.getSegments().get(0).getSourceDurationMillis()).isEqualTo(4_000L);
		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(0.5d);
		assertThat(response.getSegments().get(0).getVisualDurationMillis()).isEqualTo(8_000L);
	}

	@Test
	void fourSecondClipAtHalfXRejectsWhenOutputExceedsLockedAudio() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 4_000)
		));
		when(assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId)).thenReturn(List.of());

		assertThatThrownBy(() -> timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(0.5d).build()
		)).isInstanceOf(OutputDurationExceedsAudioException.class);
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void setSegmentSpeedRejectsImageClip() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID imageId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				VideoSegment.builder()
						.id(segmentId)
						.projectId(projectId)
						.assetId(imageId)
						.type(EditorSegmentType.IMAGE)
						.label("IMG")
						.sourceStartMillis(0L)
						.sourceEndMillis(10_000L)
						.durationMillis(10_000)
						.playbackRate(1.0d)
						.position(0)
						.build()
		));

		assertThatThrownBy(() -> timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(2.0d).build()
		)).isInstanceOf(PlaybackRateNotSupportedForImageException.class);
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void setSegmentSpeedRejectsSlowMotionLongerThanAudio() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 10_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 10_000)
		));
		when(assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId)).thenReturn(List.of());

		assertThatThrownBy(() -> timelineService.setSegmentSpeed(
				projectId,
				segmentId,
				SetEditorSegmentSpeedRequest.builder().playbackRate(0.5d).build()
		)).isInstanceOf(OutputDurationExceedsAudioException.class);
		verify(segmentRepository, never()).deleteByProjectId(projectId);
	}

	@Test
	void resetSegmentRestoresUnitySpeedWithoutChangingSourceRange() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 27_167L);
		stubMutableProject(project);
		VideoSegment clipped = videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 25_000);
		clipped.setPlaybackRate(2.0d);
		clipped.setDurationMillis(12_500L);
		List<VideoSegment> stored = new ArrayList<>(List.of(clipped));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.resetSegment(projectId, segmentId);

		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(1.0d);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(25_000L);
		assertThat(response.getOutputDurationMillis()).isEqualTo(25_000L);
		assertThat(response.getSourceDurationMillis()).isEqualTo(27_167L);
	}

	@Test
	void resetSegmentRestoresImageToOriginalVideoSlot() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		UUID imageId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		VideoSegment imageClip = VideoSegment.builder()
				.id(segmentId)
				.projectId(projectId)
				.assetId(imageId)
				.type(EditorSegmentType.IMAGE)
				.label("IMG")
				.sourceStartMillis(0L)
				.sourceEndMillis(10_000L)
				.durationMillis(10_000L)
				.playbackRate(1.0d)
				.position(0)
				.build();
		List<VideoSegment> stored = new ArrayList<>(List.of(
				imageClip,
				videoSegment(UUID.randomUUID(), projectId, sourceAssetId, 1, 10_000, 30_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.resetSegment(projectId, segmentId);

		assertThat(response.getSegments().get(0).getType()).isEqualTo(EditorSegmentType.VIDEO);
		assertThat(response.getSegments().get(0).getAssetId()).isEqualTo(sourceAssetId);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(0).getPlaybackRate()).isEqualTo(1.0d);
	}

	@Test
	void replaceVideoVisualKeepsSlotDurationAndSourceRange() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID segmentId = UUID.randomUUID();
		UUID imageId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 30_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(segmentId, projectId, sourceAssetId, 0, 0, 10_000),
				videoSegment(UUID.randomUUID(), projectId, sourceAssetId, 1, 10_000, 30_000)
		));
		stubTimelineStore(projectId, stored);
		VideoAsset image = VideoAsset.builder()
				.id(imageId)
				.projectId(projectId)
				.type(AssetType.IMAGE)
				.mimeType("image/png")
				.storagePath("assets/" + imageId + ".png")
				.storageFileName(imageId + ".png")
				.byteSize(12)
				.primarySource(false)
				.build();
		VideoAsset source = VideoAsset.builder()
				.id(sourceAssetId)
				.projectId(projectId)
				.type(AssetType.VIDEO)
				.mimeType("video/mp4")
				.storagePath("source/" + sourceAssetId + ".mp4")
				.storageFileName(sourceAssetId + ".mp4")
				.byteSize(100)
				.primarySource(true)
				.build();
		when(assetRepository.findByIdAndProjectId(imageId, projectId)).thenReturn(Optional.of(image));
		when(assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId)).thenReturn(List.of(source, image));

		EditorProjectResponse response = timelineService.replaceSegmentVisual(
				projectId,
				segmentId,
				ReplaceEditorSegmentVisualRequest.builder().assetId(imageId).build()
		);

		assertThat(response.getSegments()).hasSize(2);
		assertThat(response.getSegments().get(0).getType()).isEqualTo(EditorSegmentType.IMAGE);
		assertThat(response.getSegments().get(0).getId()).isEqualTo(segmentId.toString());
		assertThat(response.getSegments().get(0).getAssetId()).isEqualTo(imageId);
		assertThat(response.getSegments().get(0).getDurationMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(response.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(response.getSegments().get(1).getType()).isEqualTo(EditorSegmentType.VIDEO);
		assertThat(response.getVisualDurationMillis()).isEqualTo(30_000L);
	}

	@Test
	void reorderUsesClientIdOrderNotStoredPosition() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 2_000),
				videoSegment(b, projectId, sourceAssetId, 1, 2_000, 4_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse response = timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(b, a)).build()
		);

		assertThat(response.getSegments()).extracting(seg -> seg.getId())
				.containsExactly(b.toString(), a.toString());
		assertThat(response.getSegments()).extracting(seg -> seg.getPosition())
				.containsExactly(0, 1);
		assertThat(response.getSegments().get(0).getSourceStartMillis()).isEqualTo(2_000L);
		assertThat(response.getSegments().get(1).getSourceStartMillis()).isZero();
		assertThat(response.getVisualDurationMillis()).isEqualTo(4_000L);
	}

	@Test
	void reorderThenMergeRequiresVisualAndSourceContiguousNeighbors() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a1 = UUID.randomUUID();
		UUID a2 = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 20_000L);
		stubMutableProject(project);
		List<VideoSegment> stored = new ArrayList<>(List.of(
				videoSegment(a1, projectId, sourceAssetId, 0, 0, 5_000),
				videoSegment(a2, projectId, sourceAssetId, 1, 5_000, 10_000),
				videoSegment(b, projectId, sourceAssetId, 2, 10_000, 20_000)
		));
		stubTimelineStore(projectId, stored);

		EditorProjectResponse original = timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a1, a2, b)).build()
		);
		assertThat(original.getSegments().get(0).isCanMergeNext()).isTrue();
		assertThat(original.getSegments().get(0).isCanResizeRightBoundary()).isTrue();
		assertThat(original.getSegments().get(1).isCanMergeNext()).isTrue();
		assertThat(original.getSegments().get(1).isCanResizeLeftBoundary()).isTrue();

		EditorProjectResponse separated = timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a1, b, a2)).build()
		);
		assertThat(separated.getSegments()).extracting(seg -> seg.getId())
				.containsExactly(a1.toString(), b.toString(), a2.toString());
		assertThat(separated.getSegments().get(0).isCanMergeNext()).isFalse();
		assertThat(separated.getSegments().get(0).isCanResizeRightBoundary()).isFalse();
		assertThat(separated.getSegments().get(1).isCanResizeLeftBoundary()).isFalse();
		assertThatThrownBy(() -> timelineService.mergeNext(projectId, a1))
				.isInstanceOf(SegmentsNotMergeableException.class)
				.hasMessageContaining("same cut");
		assertThat(stored).hasSize(3);

		EditorProjectResponse restored = timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a1, a2, b)).build()
		);
		assertThat(restored.getSegments().get(0).isCanMergeNext()).isTrue();

		EditorProjectResponse merged = timelineService.mergeNext(projectId, a1);
		assertThat(merged.getSegments()).hasSize(2);
		assertThat(merged.getSegments().get(0).getId()).isEqualTo(a1.toString());
		assertThat(merged.getSegments().get(0).getSourceStartMillis()).isZero();
		assertThat(merged.getSegments().get(0).getSourceEndMillis()).isEqualTo(10_000L);
		assertThat(merged.getSegments().get(1).getId()).isEqualTo(b.toString());
		assertThat(merged.getSegments().get(0).isCanMergeNext()).isTrue();
	}

	@Test
	void reorderRejectsDuplicateIds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);

		assertThatThrownBy(() -> timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a, a)).build()
		)).isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("Duplicate");
		verify(segmentRepository, never()).deleteByProjectId(any());
	}

	@Test
	void reorderRejectsMissingAndForeignIds() {
		UUID projectId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		stubMutableProject(project);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				videoSegment(a, projectId, sourceAssetId, 0, 0, 2_000),
				videoSegment(b, projectId, sourceAssetId, 1, 2_000, 4_000)
		));

		assertThatThrownBy(() -> timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a)).build()
		)).isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("exactly once");

		assertThatThrownBy(() -> timelineService.reorderTimeline(
				projectId,
				ReorderEditorTimelineRequest.builder().segmentIds(List.of(a, UUID.randomUUID())).build()
		)).isInstanceOf(InvalidEditorSegmentsException.class)
				.hasMessageContaining("match the project timeline");
	}

	@Test
	void exportDownloadRequiresCompletedStatusAndExistingFile(@TempDir Path tempDir) throws Exception {
		UUID projectId = UUID.randomUUID();
		UUID exportId = UUID.randomUUID();
		UUID sourceAssetId = UUID.randomUUID();
		Path mp4 = tempDir.resolve(exportId + ".mp4");
		Files.writeString(mp4, "export-bytes");
		VideoProject project = readyProject(projectId, sourceAssetId, 4_000L);
		VideoExportJob job = VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.COMPLETED)
				.outputFilePath(mp4.toString())
				.build();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(job));
		when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
		when(editorPathResolver.toPath(mp4.toString())).thenReturn(mp4);
		doNothing().when(editorPathResolver).assertProjectExportFile(projectId, mp4);

		VideoEditorService.EditorFileDownload download = service.getExportDownload(exportId);

		assertThat(download.filename()).isEqualTo(project.getOutputBaseName() + ".mp4");
		assertThat(download.contentLength()).isEqualTo(Files.size(mp4));
		assertThat(download.resource().exists()).isTrue();
	}

	@Test
	void exportDownloadRejectsNonCompleted() {
		UUID projectId = UUID.randomUUID();
		UUID exportId = UUID.randomUUID();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.build()));
		when(projectRepository.findById(projectId)).thenReturn(Optional.of(readyProject(projectId, UUID.randomUUID(), 1_000L)));

		assertThatThrownBy(() -> service.getExportDownload(exportId))
				.isInstanceOf(ExportNotReadyException.class)
				.hasMessageContaining("COMPLETED");
	}

	@Test
	void exportDownloadRejectsUnknownExport() {
		UUID exportId = UUID.randomUUID();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getExportDownload(exportId))
				.isInstanceOf(EditorExportNotFoundException.class);
	}

	@Test
	void deleteRefusesWhileExportIsActive() {
		UUID projectId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, UUID.randomUUID(), 1_000L);
		when(projectRepository.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
		when(exportJobRepository.existsByProjectIdAndStatusIn(eq(projectId), anyList())).thenReturn(true);

		assertThatThrownBy(() -> service.delete(projectId))
				.isInstanceOf(ExportAlreadyRunningException.class);
		verify(editorPathResolver, never()).deleteProjectDirectory(projectId);
		verify(projectRepository, never()).save(any());
	}

	@Test
	void deleteSoftDeletesThenRemovesEditorOwnedFiles() {
		UUID projectId = UUID.randomUUID();
		VideoProject project = readyProject(projectId, UUID.randomUUID(), 1_000L);
		stubMutableProject(project);
		when(projectRepository.save(project)).thenReturn(project);
		doNothing().when(editorPathResolver).deleteProjectDirectory(projectId);

		service.delete(projectId);

		assertThat(project.getStatus()).isEqualTo(ProjectStatus.DELETED);
		verify(projectRepository).save(project);
		verify(editorPathResolver).deleteProjectDirectory(projectId);
	}

	private void stubMutableProject(VideoProject project) {
		when(projectRepository.findByIdForUpdate(project.getId())).thenReturn(Optional.of(project));
		when(exportJobRepository.existsByProjectIdAndStatusIn(eq(project.getId()), anyList())).thenReturn(false);
	}

	private void stubTimelineStore(UUID projectId, List<VideoSegment> stored) {
		when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenAnswer(invocation -> List.copyOf(stored));
		doNothing().when(segmentRepository).deleteByProjectId(projectId);
		when(segmentRepository.saveAll(any())).thenAnswer(invocation -> {
			List<VideoSegment> next = invocation.getArgument(0);
			stored.clear();
			stored.addAll(next);
			return stored;
		});
		when(assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId)).thenReturn(List.of());
		when(exportJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(Optional.empty());
		when(editorAssetService.list(projectId)).thenReturn(List.of());
	}

	private static VideoProject readyProject(UUID projectId, UUID sourceAssetId, long durationMillis) {
		return VideoProject.builder()
				.id(projectId)
				.status(ProjectStatus.READY)
				.sourceType(VideoEditSourceType.UPLOAD)
				.sourceAssetId(sourceAssetId)
				.outputBaseName("edit_" + projectId.toString().substring(0, 8))
				.hasVideo(true)
				.hasAudio(true)
				.width(1280)
				.height(720)
				.fps(30.0d)
				.durationMillis(durationMillis)
				.exportFps("ORIGINAL")
				.exportResolution("ORIGINAL")
				.exportCodec("H264")
				.build();
	}

	private static VideoSegment videoSegment(
			UUID id,
			UUID projectId,
			UUID assetId,
			int position,
			long start,
			long end
	) {
		return VideoSegment.builder()
				.id(id)
				.projectId(projectId)
				.assetId(assetId)
				.type(EditorSegmentType.VIDEO)
				.label(switch (position) {
					case 0 -> "A";
					case 1 -> "B";
					case 2 -> "C";
					default -> "S" + position;
				})
				.sourceStartMillis(start)
				.sourceEndMillis(end)
				.durationMillis(end - start)
				.playbackRate(1.0d)
				.position(position)
				.build();
	}
}
