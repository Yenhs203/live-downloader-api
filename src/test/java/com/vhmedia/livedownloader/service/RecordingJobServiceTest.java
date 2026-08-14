package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.dto.response.RecordingJobResponse;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.exception.ConcurrentRecordingLimitException;
import com.vhmedia.livedownloader.exception.FfmpegStartException;
import com.vhmedia.livedownloader.exception.InvalidRecordingStateException;
import com.vhmedia.livedownloader.exception.NoVideoStreamException;
import com.vhmedia.livedownloader.exception.RecordingNotFoundException;
import com.vhmedia.livedownloader.exception.StorageException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.media.FfmpegRecordingService;
import com.vhmedia.livedownloader.media.JobStatusChangedEvent;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import com.vhmedia.livedownloader.util.StreamUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordingJobServiceTest {

	@Mock
	private StreamUrlValidator streamUrlValidator;
	@Mock
	private FfprobeService ffprobeService;
	@Mock
	private FfmpegRecordingService ffmpegRecordingService;
	@Mock
	private LiveDownloadJobRepository jobRepository;
	@Mock
	private RecordingPathResolver pathResolver;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private RecordingJobService service;

	@BeforeEach
	void setUp() {
		service = new RecordingJobService(
				streamUrlValidator,
				ffprobeService,
				ffmpegRecordingService,
				jobRepository,
				pathResolver,
				eventPublisher
		);
	}

	@Test
	void createAndStartProbesPersistsAndStartsAsync() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenReturn(probeWithVideo());

		RecordingJobResponse response = service.createAndStart("https://cdn.example.com/live.flv?token=abc");

		assertThat(response.getStatus()).isEqualTo(LiveJobStatus.RECORDING);
		assertThat(response.isHasVideo()).isTrue();
		assertThat(response.getVideoCodec()).isEqualTo("h264");
		assertThat(response.getStartedAt()).isNotNull();

		ArgumentCaptor<UUID> jobIdCaptor = ArgumentCaptor.forClass(UUID.class);
		verify(ffmpegRecordingService).startRecording(jobIdCaptor.capture(), eq("https://cdn.example.com/live.flv?token=abc"), any(Path.class));
		assertThat(jobIdCaptor.getValue()).isEqualTo(response.getId());

		ArgumentCaptor<JobStatusChangedEvent> eventCaptor = ArgumentCaptor.forClass(JobStatusChangedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().jobId()).isEqualTo(response.getId());
		assertThat(eventCaptor.getValue().status()).isEqualTo(LiveJobStatus.RECORDING);
	}

	@Test
	void createAndStartMarksFailedOnProbeTimeout() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenThrow(new StreamProbeTimeoutException("timed out"));

		assertThatThrownBy(() -> service.createAndStart("https://cdn.example.com/live.flv"))
				.isInstanceOf(StreamProbeTimeoutException.class);

		ArgumentCaptor<LiveDownloadJob> jobCaptor = ArgumentCaptor.forClass(LiveDownloadJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		assertThat(jobCaptor.getAllValues().stream().anyMatch(j -> j.getStatus() == LiveJobStatus.FAILED)).isTrue();
		verify(ffmpegRecordingService, never()).startRecording(any(), anyString(), any());
	}

	@Test
	void createAndStartMarksFailedOnProbeError() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenThrow(new StreamProbeException("boom"));

		assertThatThrownBy(() -> service.createAndStart("https://cdn.example.com/live.flv"))
				.isInstanceOf(StreamProbeException.class);

		verify(ffmpegRecordingService, never()).startRecording(any(), anyString(), any());
	}

	@Test
	void createAndStartPropagatesConcurrentLimit() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenReturn(probeWithVideo());
		doThrow(new ConcurrentRecordingLimitException("Maximum concurrent recordings exceeded (2)"))
				.when(ffmpegRecordingService).startRecording(any(), anyString(), any());

		assertThatThrownBy(() -> service.createAndStart("https://cdn.example.com/live.flv"))
				.isInstanceOf(ConcurrentRecordingLimitException.class);
	}

	@Test
	void createAndStartWrapsUnexpectedStartFailure() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenReturn(probeWithVideo());
		doThrow(new IllegalStateException("pool rejected"))
				.when(ffmpegRecordingService).startRecording(any(), anyString(), any());

		assertThatThrownBy(() -> service.createAndStart("https://cdn.example.com/live.flv"))
				.isInstanceOf(FfmpegStartException.class);
	}

	@Test
	void rejectsStreamWithoutVideo() {
		stubCreatePaths();
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		when(ffprobeService.probe(anyString())).thenReturn(
				StreamProbeResult.builder().hasVideo(false).hasAudio(true).audioCodec("aac").build()
		);

		assertThatThrownBy(() -> service.createAndStart("https://cdn.example.com/audio-only"))
				.isInstanceOf(NoVideoStreamException.class);
		verify(ffmpegRecordingService, never()).startRecording(any(), anyString(), any());
	}

	@Test
	void listExcludesDeletedByDefault() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findByStatusNot(eq(LiveJobStatus.DELETED), any()))
				.thenReturn(new PageImpl<>(List.of(completedJob(id, "base"))));

		Page<RecordingJobResponse> page = service.list(null, false, PageRequest.of(0, 10, Sort.by("createdAt").descending()));

		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().getFirst().getId()).isEqualTo(id);
	}

	@Test
	void listFiltersByStatus() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findByStatus(eq(LiveJobStatus.COMPLETED), any()))
				.thenReturn(new PageImpl<>(List.of(completedJob(id, "base"))));

		Page<RecordingJobResponse> page = service.list(LiveJobStatus.COMPLETED, false, PageRequest.of(0, 10));

		assertThat(page.getContent()).hasSize(1);
		verify(jobRepository).findByStatus(eq(LiveJobStatus.COMPLETED), any());
	}

	@Test
	void getReturnsVisibleJob() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.of(job(id, LiveJobStatus.COMPLETED, "base")));

		RecordingJobResponse response = service.get(id);

		assertThat(response.getId()).isEqualTo(id);
		assertThat(response.getStatus()).isEqualTo(LiveJobStatus.COMPLETED);
	}

	@Test
	void getThrowsWhenMissing() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(id)).isInstanceOf(RecordingNotFoundException.class);
	}

	@Test
	void getTreatsDeletedAsNotFound() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.of(job(id, LiveJobStatus.DELETED, "base")));

		assertThatThrownBy(() -> service.get(id)).isInstanceOf(RecordingNotFoundException.class);
	}

	@Test
	void deleteRejectsRecordingStatusWhileProcessRunning() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.of(job(id, LiveJobStatus.RECORDING, "base")));
		when(ffmpegRecordingService.isRunning(id)).thenReturn(true);

		assertThatThrownBy(() -> service.delete(id))
				.isInstanceOf(InvalidRecordingStateException.class)
				.hasMessageContaining("RECORDING");
	}

	@Test
	void deleteAllowsOrphanedRecordingWhenProcessGone() {
		UUID id = UUID.randomUUID();
		LiveDownloadJob orphan = job(id, LiveJobStatus.RECORDING, "base");
		when(jobRepository.findById(id)).thenReturn(Optional.of(orphan));
		when(ffmpegRecordingService.isRunning(id)).thenReturn(false);
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.delete(id);

		assertThat(orphan.getStatus()).isEqualTo(LiveJobStatus.DELETED);
	}

	@Test
	void deleteAllowsReadyStuckJob() {
		UUID id = UUID.randomUUID();
		LiveDownloadJob ready = job(id, LiveJobStatus.READY, "base");
		when(jobRepository.findById(id)).thenReturn(Optional.of(ready));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.delete(id);

		assertThat(ready.getStatus()).isEqualTo(LiveJobStatus.DELETED);
	}

	@Test
	void deleteRejectsStoppingAndRemuxing() {
		UUID stoppingId = UUID.randomUUID();
		when(jobRepository.findById(stoppingId)).thenReturn(Optional.of(job(stoppingId, LiveJobStatus.STOPPING, "s")));
		assertThatThrownBy(() -> service.delete(stoppingId)).isInstanceOf(InvalidRecordingStateException.class);

		UUID remuxId = UUID.randomUUID();
		when(jobRepository.findById(remuxId)).thenReturn(Optional.of(job(remuxId, LiveJobStatus.REMUXING, "r")));
		assertThatThrownBy(() -> service.delete(remuxId)).isInstanceOf(InvalidRecordingStateException.class);
	}

	@Test
	void deleteCompletedRemovesFilesAndSoftDeletes(@TempDir Path tempDir) throws Exception {
		UUID id = UUID.randomUUID();
		Path ts = tempDir.resolve("base.ts");
		Path mp4 = tempDir.resolve("base.mp4");
		Files.writeString(ts, "ts");
		Files.writeString(mp4, "mp4");

		LiveDownloadJob job = job(id, LiveJobStatus.COMPLETED, "base");
		job.setTempFilePath(ts.toString());
		job.setFinalFilePath(mp4.toString());
		when(jobRepository.findById(id)).thenReturn(Optional.of(job));
		when(pathResolver.toPath(ts.toString())).thenReturn(ts);
		when(pathResolver.toPath(mp4.toString())).thenReturn(mp4);
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.delete(id);

		assertThat(Files.exists(ts)).isFalse();
		assertThat(Files.exists(mp4)).isFalse();
		assertThat(job.getStatus()).isEqualTo(LiveJobStatus.DELETED);
	}

	@Test
	void downloadOnlyAllowsCompletedAndValidatesPath(@TempDir Path tempDir) throws Exception {
		UUID id = UUID.randomUUID();
		Path mp4 = tempDir.resolve("base.mp4");
		Files.writeString(mp4, "video-bytes");

		LiveDownloadJob job = job(id, LiveJobStatus.COMPLETED, "base");
		job.setFinalFilePath(mp4.toString());
		when(jobRepository.findById(id)).thenReturn(Optional.of(job));
		when(pathResolver.resolveMp4Path("base")).thenReturn(mp4);
		when(pathResolver.toPath(mp4.toString())).thenReturn(mp4);

		RecordingJobService.RecordingFileDownload download = service.getDownload(id);

		assertThat(download.filename()).isEqualTo("base.mp4");
		assertThat(download.contentLength()).isEqualTo(Files.size(mp4));
		assertThat(download.resource().exists()).isTrue();
	}

	@Test
	void downloadRejectsNonCompletedStatus() {
		UUID id = UUID.randomUUID();
		when(jobRepository.findById(id)).thenReturn(Optional.of(job(id, LiveJobStatus.RECORDING, "base")));

		assertThatThrownBy(() -> service.getDownload(id))
				.isInstanceOf(InvalidRecordingStateException.class)
				.hasMessageContaining("COMPLETED");
	}

	@Test
	void downloadRejectsPathMismatch(@TempDir Path tempDir) throws Exception {
		UUID id = UUID.randomUUID();
		Path expected = tempDir.resolve("base.mp4");
		Path actual = tempDir.resolve("other.mp4");
		Files.writeString(actual, "x");

		LiveDownloadJob job = job(id, LiveJobStatus.COMPLETED, "base");
		job.setFinalFilePath(actual.toString());
		when(jobRepository.findById(id)).thenReturn(Optional.of(job));
		when(pathResolver.resolveMp4Path("base")).thenReturn(expected);
		when(pathResolver.toPath(actual.toString())).thenReturn(actual);

		assertThatThrownBy(() -> service.getDownload(id)).isInstanceOf(StorageException.class);
	}

	@Test
	void downloadRejectsMissingFileOnDisk(@TempDir Path tempDir) {
		UUID id = UUID.randomUUID();
		Path mp4 = tempDir.resolve("missing.mp4");

		LiveDownloadJob job = job(id, LiveJobStatus.COMPLETED, "base");
		job.setFinalFilePath(mp4.toString());
		when(jobRepository.findById(id)).thenReturn(Optional.of(job));
		when(pathResolver.resolveMp4Path("base")).thenReturn(mp4);
		when(pathResolver.toPath(mp4.toString())).thenReturn(mp4);

		assertThatThrownBy(() -> service.getDownload(id)).isInstanceOf(StorageException.class);
	}

	private void stubCreatePaths() {
		doNothing().when(streamUrlValidator).validate(anyString());
		when(pathResolver.resolveTsPath(anyString())).thenAnswer(inv -> Path.of("recordings", inv.getArgument(0) + ".ts"));
		when(pathResolver.resolveMp4Path(anyString())).thenAnswer(inv -> Path.of("recordings", inv.getArgument(0) + ".mp4"));
	}

	private static StreamProbeResult probeWithVideo() {
		return StreamProbeResult.builder()
				.hasVideo(true)
				.hasAudio(true)
				.videoCodec("h264")
				.audioCodec("aac")
				.width(1080)
				.height(1920)
				.fps(30.0d)
				.formatName("flv")
				.build();
	}

	private static LiveDownloadJob completedJob(UUID id, String base) {
		return job(id, LiveJobStatus.COMPLETED, base);
	}

	private static LiveDownloadJob job(UUID id, LiveJobStatus status, String base) {
		return LiveDownloadJob.builder()
				.id(id)
				.originalUrl("https://cdn.example.com/live.flv")
				.outputBaseName(base)
				.status(status)
				.videoCodec("h264")
				.audioCodec("aac")
				.width(1080)
				.height(1920)
				.createdAt(Instant.now())
				.updatedAt(Instant.now())
				.build();
	}
}
