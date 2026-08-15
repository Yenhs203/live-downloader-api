package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.entity.VideoProject;
import com.vhmedia.livedownloader.entity.VideoSegment;
import com.vhmedia.livedownloader.enums.EditorSegmentType;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.enums.ProjectStatus;
import com.vhmedia.livedownloader.enums.VideoEditSourceType;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import com.vhmedia.livedownloader.repository.VideoProjectRepository;
import com.vhmedia.livedownloader.repository.VideoSegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditorEventHubTest {

	@Mock
	private VideoProjectRepository projectRepository;

	@Mock
	private VideoExportJobRepository exportJobRepository;

	@Mock
	private VideoSegmentRepository segmentRepository;

	private EditorEventHub hub;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setSseTimeoutSeconds(60);
		hub = new EditorEventHub(projectRepository, exportJobRepository, segmentRepository, mediaProperties);
		lenient().when(segmentRepository.findByProjectIdOrderByPositionAsc(any())).thenReturn(List.of());
	}

	@Test
	void subscribeExportSendsSnapshotImmediately() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(exportJob(exportId, projectId)));
		when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId)));

		SseEmitter emitter = hub.subscribeExport(exportId);

		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(60_000L);
	}

	@Test
	void progressAndStatusEventsDoNotThrow() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(exportJob(exportId, projectId)));
		when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId)));

		hub.subscribeExport(exportId);
		hub.subscribe(projectId);

		hub.onProgress(new EditorProgressEvent(
				projectId,
				exportId,
				RecordingProgress.builder()
						.outTimeMs(23_000L)
						.fps(42.1d)
						.speed("1.7x")
						.build(),
				60_000L
		));
		hub.onStatusChanged(new EditorStatusChangedEvent(projectId, exportId, ExportStatus.FINALIZING));
		hub.onStatusChanged(new EditorStatusChangedEvent(projectId, exportId, ExportStatus.COMPLETED));
	}

	@Test
	void percentCapsAtOneHundred() {
		assertThat(EditorEventHub.percent(23_000L, 60_000L)).isCloseTo(38.33d, within(0.01d));
		assertThat(EditorEventHub.percent(90_000L, 60_000L)).isEqualTo(100.0d);
		assertThat(EditorEventHub.percent(null, 60_000L)).isNull();
		assertThat(EditorEventHub.percent(25_000L, 25_000L)).isEqualTo(100.0d);
	}

	@Test
	void progressDenominatorUsesOutputDurationNotSource() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		VideoProject project = project(projectId);
		project.setDurationMillis(27_167L);
		when(segmentRepository.findByProjectIdOrderByPositionAsc(projectId)).thenReturn(List.of(
				VideoSegment.builder()
						.id(UUID.randomUUID())
						.projectId(projectId)
						.type(EditorSegmentType.VIDEO)
						.label("A")
						.sourceStartMillis(0L)
						.sourceEndMillis(25_000L)
						.durationMillis(25_000L)
						.playbackRate(1.0d)
						.position(0)
						.build()
		));

		assertThat(hub.resolveOutputDurationMillis(projectId, exportId, project, 25_000L)).isEqualTo(25_000L);
		assertThat(hub.resolveOutputDurationMillis(projectId, exportId, project, null)).isEqualTo(25_000L);
		assertThat(EditorEventHub.percent(25_000L, 25_000L)).isEqualTo(100.0d);
		assertThat(EditorEventHub.percent(25_000L, 27_167L)).isLessThan(100.0d);
	}

	@Test
	void progressEventCachesOutputDurationForLaterStatusTicks() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		VideoProject project = project(projectId);
		project.setDurationMillis(27_167L);
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(exportJob(exportId, projectId)));
		when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

		hub.onProgress(new EditorProgressEvent(
				projectId,
				exportId,
				RecordingProgress.builder().outTimeMs(25_000L).build(),
				25_000L
		));

		assertThat(hub.resolveOutputDurationMillis(projectId, exportId, project, null)).isEqualTo(25_000L);
		assertThat(EditorEventHub.percent(25_000L, hub.resolveOutputDurationMillis(projectId, exportId, project, null)))
				.isEqualTo(100.0d);
	}

	private static VideoExportJob exportJob(UUID exportId, UUID projectId) {
		return VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.fpsPreset("ORIGINAL")
				.resolution("ORIGINAL")
				.videoCodec("H264")
				.build();
	}

	private static VideoProject project(UUID projectId) {
		return VideoProject.builder()
				.id(projectId)
				.status(ProjectStatus.READY)
				.sourceType(VideoEditSourceType.UPLOAD)
				.outputBaseName("edit_" + projectId)
				.durationMillis(60_000L)
				.build();
	}
}
