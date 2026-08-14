package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingEventHubTest {

	@Mock
	private LiveDownloadJobRepository jobRepository;

	private RecordingEventHub hub;

	@BeforeEach
	void setUp() {
		MediaProperties mediaProperties = new MediaProperties();
		mediaProperties.setSseTimeoutSeconds(60);
		hub = new RecordingEventHub(jobRepository, mediaProperties);
	}

	@Test
	void subscribeSendsCurrentSnapshotImmediately() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(
				LiveDownloadJob.builder()
						.id(jobId)
						.status(LiveJobStatus.RECORDING)
						.downloadedBytes(104_857_600L)
						.durationMillis(123_000L)
						.fps(30.0d)
						.build()
		));

		SseEmitter emitter = hub.subscribe(jobId);
		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(60_000L);
	}

	@Test
	void statusEventsDoNotThrowForNamedTransitions() {
		UUID jobId = UUID.randomUUID();

		hub.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder()
						.outTimeMs(123_000L)
						.totalSize(104_857_600L)
						.fps(30.0d)
						.speed("1.0x")
						.bitrate("4500kbits/s")
						.build()
		));

		when(jobRepository.findById(jobId)).thenReturn(Optional.of(
				LiveDownloadJob.builder().id(jobId).status(LiveJobStatus.RECORDING).build()
		));
		hub.subscribe(jobId);

		hub.onStatusChanged(new JobStatusChangedEvent(jobId, LiveJobStatus.STOPPING));
		hub.onStatusChanged(new JobStatusChangedEvent(jobId, LiveJobStatus.REMUXING, 104_857_600L, 123_000L));
		hub.onStatusChanged(new JobStatusChangedEvent(jobId, LiveJobStatus.COMPLETED, 104_857_600L, 123_000L));
	}

	@Test
	void supportsMultipleListenersPerJob() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(
				LiveDownloadJob.builder().id(jobId).status(LiveJobStatus.RECORDING).build()
		));

		SseEmitter first = hub.subscribe(jobId);
		SseEmitter second = hub.subscribe(jobId);

		assertThat(first).isNotSameAs(second);

		hub.onProgress(new RecordingProgressEvent(
				jobId,
				RecordingProgress.builder()
						.outTimeMs(1_000L)
						.totalSize(2_000L)
						.fps(30.0d)
						.speed("1.0x")
						.bitrate("4500kbits/s")
						.build()
		));
	}
}
