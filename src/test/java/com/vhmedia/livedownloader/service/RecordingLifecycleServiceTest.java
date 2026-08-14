package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.config.MediaProperties;
import com.vhmedia.livedownloader.config.RemuxTaskExecutor;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.exception.RemuxException;
import com.vhmedia.livedownloader.media.FfmpegRecordingService;
import com.vhmedia.livedownloader.media.FfmpegRemuxService;
import com.vhmedia.livedownloader.media.JobStatusChangedEvent;
import com.vhmedia.livedownloader.media.RecordingExitReason;
import com.vhmedia.livedownloader.media.RecordingExitResult;
import com.vhmedia.livedownloader.media.RecordingFinishedEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingLifecycleServiceTest {

	@Mock
	private LiveDownloadJobRepository jobRepository;
	@Mock
	private FfmpegRecordingService ffmpegRecordingService;
	@Mock
	private FfmpegRemuxService ffmpegRemuxService;
	@Mock
	private RecordingPathResolver pathResolver;
	@Mock
	private RemuxTaskExecutor remuxTaskExecutor;
	@Mock
	private MediaProperties mediaProperties;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private RecordingProgressPersistenceService progressPersistenceService;
	@Mock
	private PlatformTransactionManager transactionManager;

	private RecordingLifecycleService service;

	@BeforeEach
	void setUp() {
		lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new RecordingLifecycleService(
				jobRepository,
				ffmpegRecordingService,
				ffmpegRemuxService,
				pathResolver,
				remuxTaskExecutor,
				mediaProperties,
				eventPublisher,
				progressPersistenceService,
				transactionManager
		);
	}

	@Test
	void requestStopTransitionsRecordingAndSignalsFfmpeg() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(jobId, LiveJobStatus.RECORDING)));
		when(jobRepository.transitionStatus(eq(jobId), eq(LiveJobStatus.RECORDING), eq(LiveJobStatus.STOPPING), any()))
				.thenReturn(1);
		when(ffmpegRecordingService.requestGracefulStop(jobId)).thenReturn(true);

		service.requestStop(jobId);

		verify(ffmpegRecordingService).requestGracefulStop(jobId);
		ArgumentCaptor<JobStatusChangedEvent> eventCaptor = ArgumentCaptor.forClass(JobStatusChangedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().status()).isEqualTo(LiveJobStatus.STOPPING);
	}

	@Test
	void requestStopIsIdempotentWhenAlreadyStopping() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(jobId, LiveJobStatus.STOPPING)));
		when(jobRepository.transitionStatus(eq(jobId), eq(LiveJobStatus.RECORDING), eq(LiveJobStatus.STOPPING), any()))
				.thenReturn(0);

		assertThatCode(() -> service.requestStop(jobId)).doesNotThrowAnyException();
		verify(ffmpegRecordingService, never()).requestGracefulStop(any());
	}

	@Test
	void requestStopIsIdempotentWhenAlreadyRemuxing() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(jobId, LiveJobStatus.REMUXING)));
		when(jobRepository.transitionStatus(eq(jobId), eq(LiveJobStatus.RECORDING), eq(LiveJobStatus.STOPPING), any()))
				.thenReturn(0);

		assertThatCode(() -> service.requestStop(jobId)).doesNotThrowAnyException();
		verify(ffmpegRecordingService, never()).requestGracefulStop(any());
	}

	@Test
	void requestStopRejectsFailedJobs() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(jobId, LiveJobStatus.FAILED)));
		when(jobRepository.transitionStatus(eq(jobId), eq(LiveJobStatus.RECORDING), eq(LiveJobStatus.STOPPING), any()))
				.thenReturn(0);

		assertThatThrownBy(() -> service.requestStop(jobId))
				.isInstanceOf(InvalidRecordingStateException.class)
				.hasMessageContaining("FAILED");
	}

	@Test
	void requestStopRejectsMissingJob() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requestStop(jobId))
				.isInstanceOf(RecordingNotFoundException.class);
	}

	@Test
	void requestStopHandlesMissingProcessRace() {
		UUID jobId = UUID.randomUUID();
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(jobId, LiveJobStatus.RECORDING)));
		when(jobRepository.transitionStatus(eq(jobId), eq(LiveJobStatus.RECORDING), eq(LiveJobStatus.STOPPING), any()))
				.thenReturn(1);
		when(ffmpegRecordingService.requestGracefulStop(jobId)).thenReturn(false);

		assertThatCode(() -> service.requestStop(jobId)).doesNotThrowAnyException();
		verify(ffmpegRecordingService).requestGracefulStop(jobId);
	}

	@Test
	void finishedFailureMarksJobFailed(@TempDir Path tempDir) {
		UUID jobId = UUID.randomUUID();
		LiveDownloadJob recording = job(jobId, LiveJobStatus.RECORDING);
		when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(recording));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onRecordingFinished(new RecordingFinishedEvent(RecordingExitResult.builder()
				.jobId(jobId)
				.reason(RecordingExitReason.FAILED)
				.exitCode(1)
				.errorMessage("ffmpeg crashed")
				.build()));

		assertThat(recording.getStatus()).isEqualTo(LiveJobStatus.FAILED);
		assertThat(recording.getErrorMessage()).contains("ffmpeg crashed");
		verify(eventPublisher).publishEvent(any(JobStatusChangedEvent.class));
		verify(ffmpegRemuxService, never()).remux(any(), any());
	}

	@Test
	void finishedSuccessClaimsRemuxAndCompletes(@TempDir Path tempDir) throws Exception {
		UUID jobId = UUID.randomUUID();
		Path ts = tempDir.resolve("job.ts");
		Path mp4 = tempDir.resolve("job.mp4");
		Files.writeString(ts, "mpegts-bytes");

		LiveDownloadJob stopping = job(jobId, LiveJobStatus.STOPPING);
		stopping.setOutputBaseName("job");
		LiveDownloadJob remuxing = job(jobId, LiveJobStatus.REMUXING);
		remuxing.setOutputBaseName("job");

		when(jobRepository.findByIdForUpdate(jobId))
				.thenReturn(Optional.of(stopping))
				.thenReturn(Optional.of(remuxing));
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(remuxing));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(pathResolver.resolveMp4Path("job")).thenReturn(mp4);
		when(ffmpegRemuxService.remux(ts, mp4)).thenReturn(42L);
		when(mediaProperties.isDeleteTempAfterRemux()).thenReturn(true);
		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(remuxTaskExecutor).execute(any(Runnable.class));

		service.onRecordingFinished(new RecordingFinishedEvent(RecordingExitResult.builder()
				.jobId(jobId)
				.reason(RecordingExitReason.STOPPED_BY_USER)
				.exitCode(0)
				.outputPath(ts)
				.downloadedBytes(12L)
				.durationMillis(1000L)
				.build()));

		assertThat(stopping.getStatus()).isEqualTo(LiveJobStatus.REMUXING);
		assertThat(remuxing.getStatus()).isEqualTo(LiveJobStatus.COMPLETED);
		assertThat(remuxing.getFinalFilePath()).isEqualTo(mp4.toString());
		assertThat(remuxing.getDownloadedBytes()).isEqualTo(42L);
		assertThat(Files.exists(ts)).isFalse();
		verify(ffmpegRemuxService).remux(ts, mp4);
	}

	@Test
	void remuxFailureMarksFailedAndKeepsTempTs(@TempDir Path tempDir) throws Exception {
		UUID jobId = UUID.randomUUID();
		Path ts = tempDir.resolve("job.ts");
		Path mp4 = tempDir.resolve("job.mp4");
		Files.writeString(ts, "mpegts-bytes");

		LiveDownloadJob stopping = job(jobId, LiveJobStatus.STOPPING);
		stopping.setOutputBaseName("job");
		LiveDownloadJob remuxing = job(jobId, LiveJobStatus.REMUXING);
		remuxing.setOutputBaseName("job");

		when(jobRepository.findByIdForUpdate(jobId))
				.thenReturn(Optional.of(stopping))
				.thenReturn(Optional.of(remuxing));
		when(jobRepository.findById(jobId)).thenReturn(Optional.of(remuxing));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(pathResolver.resolveMp4Path("job")).thenReturn(mp4);
		when(ffmpegRemuxService.remux(ts, mp4)).thenThrow(new RemuxException("remux boom"));
		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(remuxTaskExecutor).execute(any(Runnable.class));

		service.onRecordingFinished(new RecordingFinishedEvent(RecordingExitResult.builder()
				.jobId(jobId)
				.reason(RecordingExitReason.COMPLETED_NATURALLY)
				.exitCode(0)
				.outputPath(ts)
				.build()));

		assertThat(remuxing.getStatus()).isEqualTo(LiveJobStatus.FAILED);
		assertThat(Files.exists(ts)).isTrue();
	}

	@Test
	void emptyTempTsMarksFailedWithoutRemux(@TempDir Path tempDir) throws Exception {
		UUID jobId = UUID.randomUUID();
		Path ts = tempDir.resolve("empty.ts");
		Files.createFile(ts);

		LiveDownloadJob recording = job(jobId, LiveJobStatus.RECORDING);
		when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(recording));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.onRecordingFinished(new RecordingFinishedEvent(RecordingExitResult.builder()
				.jobId(jobId)
				.reason(RecordingExitReason.STOPPED_BY_USER)
				.exitCode(0)
				.outputPath(ts)
				.build()));

		assertThat(recording.getStatus()).isEqualTo(LiveJobStatus.FAILED);
		verify(ffmpegRemuxService, never()).remux(any(), any());
		verify(remuxTaskExecutor, never()).execute(any());
	}

	@Test
	void skipsRemuxWhenAlreadyClaimed(@TempDir Path tempDir) throws Exception {
		UUID jobId = UUID.randomUUID();
		Path ts = tempDir.resolve("job.ts");
		Files.writeString(ts, "mpegts-bytes");

		LiveDownloadJob completed = job(jobId, LiveJobStatus.COMPLETED);
		when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(completed));

		service.onRecordingFinished(new RecordingFinishedEvent(RecordingExitResult.builder()
				.jobId(jobId)
				.reason(RecordingExitReason.STOPPED_BY_USER)
				.exitCode(0)
				.outputPath(ts)
				.build()));

		verify(remuxTaskExecutor, never()).execute(any());
		verify(ffmpegRemuxService, never()).remux(any(), any());
	}

	private static LiveDownloadJob job(UUID id, LiveJobStatus status) {
		return LiveDownloadJob.builder()
				.id(id)
				.originalUrl("https://cdn.example.com/live.flv")
				.outputBaseName("job-" + id)
				.status(status)
				.createdAt(Instant.now())
				.updatedAt(Instant.now())
				.build();
	}
}
