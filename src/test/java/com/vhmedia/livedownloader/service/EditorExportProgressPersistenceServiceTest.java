package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.media.EditorProgressEvent;
import com.vhmedia.livedownloader.media.EditorStatusChangedEvent;
import com.vhmedia.livedownloader.media.RecordingProgress;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditorExportProgressPersistenceServiceTest {

	@Mock
	private VideoExportJobRepository exportJobRepository;

	private EditorExportProgressPersistenceService service;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setProgressPersistIntervalSeconds(3);
		service = new EditorExportProgressPersistenceService(exportJobRepository, mediaProperties);
	}

	@Test
	void throttlesDbWritesWithinInterval() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		VideoExportJob job = VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.build();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(job));
		when(exportJobRepository.save(any(VideoExportJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onProgress(progressEvent(projectId, exportId, 1_000L));
		service.onProgress(progressEvent(projectId, exportId, 2_000L));
		service.onProgress(progressEvent(projectId, exportId, 3_000L));

		verify(exportJobRepository, times(1)).save(any(VideoExportJob.class));
	}

	@Test
	void persistsPercentAgainstOutputDurationNotSource() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		VideoExportJob job = VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.build();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(job));
		when(exportJobRepository.save(any(VideoExportJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onProgress(new EditorProgressEvent(
				projectId,
				exportId,
				RecordingProgress.builder().outTimeMs(25_000L).build(),
				25_000L
		));

		assertThat(job.getProgressMillis()).isEqualTo(25_000L);
		assertThat(job.getProgressPercent()).isEqualTo(100.0d);
	}

	@Test
	void skipsPersistWhenExportIdIsMissing() {
		service.onProgress(new EditorProgressEvent(
				UUID.randomUUID(),
				RecordingProgress.builder().outTimeMs(1_000L).build(),
				60_000L
		));

		verify(exportJobRepository, never()).findById(any());
		verify(exportJobRepository, never()).save(any());
	}

	@Test
	void clearsThrottleOnTerminalStatus() {
		UUID exportId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		VideoExportJob job = VideoExportJob.builder()
				.id(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.build();
		when(exportJobRepository.findById(exportId)).thenReturn(Optional.of(job));
		when(exportJobRepository.save(any(VideoExportJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onProgress(progressEvent(projectId, exportId, 1_000L));
		service.onStatusChanged(new EditorStatusChangedEvent(projectId, exportId, ExportStatus.COMPLETED));
		service.onProgress(progressEvent(projectId, exportId, 2_000L));

		verify(exportJobRepository, times(2)).save(any(VideoExportJob.class));
	}

	private static EditorProgressEvent progressEvent(UUID projectId, UUID exportId, long processedMillis) {
		return new EditorProgressEvent(
				projectId,
				exportId,
				RecordingProgress.builder().outTimeMs(processedMillis).build(),
				60_000L
		);
	}
}
