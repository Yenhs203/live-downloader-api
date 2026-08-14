package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.media.RecordingProgress;
import com.vhmedia.livedownloader.media.RecordingProgressEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingProgressPersistenceServiceTest {

	@Mock
	private LiveDownloadJobRepository jobRepository;

	private RecordingProgressPersistenceService service;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setProgressPersistIntervalSeconds(3);
		service = new RecordingProgressPersistenceService(jobRepository, mediaProperties);
	}

	@Test
	void throttlesDbWritesWithinInterval() {
		UUID jobId = UUID.randomUUID();
		LiveDownloadJob job = LiveDownloadJob.builder()
				.id(jobId)
				.status(LiveJobStatus.RECORDING)
				.build();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		RecordingProgress progress = RecordingProgress.builder()
				.outTimeMs(1_000L)
				.totalSize(10_000L)
				.fps(30.0d)
				.build();

		service.onProgress(new RecordingProgressEvent(jobId, progress));
		service.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder().outTimeMs(2_000L).totalSize(10_000L).fps(30.0d).build()
		));
		service.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder().outTimeMs(3_000L).totalSize(10_000L).fps(30.0d).build()
		));

		verify(jobRepository, times(1)).save(any(LiveDownloadJob.class));
	}

	@Test
	void persistsAgainAfterClear() {
		UUID jobId = UUID.randomUUID();
		LiveDownloadJob job = LiveDownloadJob.builder()
				.id(jobId)
				.status(LiveJobStatus.RECORDING)
				.build();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder().outTimeMs(1_000L).totalSize(10_000L).build()
		));
		service.clear(jobId);
		service.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder().outTimeMs(2_000L).totalSize(10_000L).build()
		));

		verify(jobRepository, times(2)).save(any(LiveDownloadJob.class));
	}

	@Test
	void skipsNonRecordingJobs() {
		UUID jobId = UUID.randomUUID();
		LiveDownloadJob job = LiveDownloadJob.builder()
				.id(jobId)
				.status(LiveJobStatus.STOPPING)
				.build();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

		service.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder().outTimeMs(1_000L).totalSize(10_000L).build()
		));

		verify(jobRepository, never()).save(any());
	}
}
