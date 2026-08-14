package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;
import com.vhmedia.livedownloader.repository.LiveDownloadJobRepository;
import com.vhmedia.livedownloader.util.RecordingPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartupJobRecoveryServiceTest {

	@Mock
	private LiveDownloadJobRepository jobRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	@TempDir
	Path tempDir;

	private RecordingPathResolver pathResolver;
	private StartupJobRecoveryService service;

	@BeforeEach
	void setUp() {
		com.vhmedia.livedownloader.config.MediaProperties mediaProperties =
				new com.vhmedia.livedownloader.config.MediaProperties();
		mediaProperties.setRecordingsDirectory(tempDir.toString());
		pathResolver = new RecordingPathResolver(mediaProperties);
		lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new StartupJobRecoveryService(jobRepository, pathResolver, transactionManager);
	}

	@Test
	void doesNothingWhenNoActiveJobs() {
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of());

		service.recoverOnStartup();

		verify(jobRepository, never()).save(any());
	}

	@Test
	void marksRecordingAndStoppingAsInterrupted() {
		LiveDownloadJob recording = job("rec_base", LiveJobStatus.RECORDING);
		LiveDownloadJob stopping = job("stop_base", LiveJobStatus.STOPPING);
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of(recording, stopping));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		assertThat(recording.getStatus()).isEqualTo(LiveJobStatus.INTERRUPTED);
		assertThat(stopping.getStatus()).isEqualTo(LiveJobStatus.INTERRUPTED);
		assertThat(recording.getErrorMessage()).isEqualTo(StartupJobRecoveryService.INTERRUPTED_MESSAGE);
		assertThat(recording.getStoppedAt()).isNotNull();
		assertThat(stopping.getStoppedAt()).isNotNull();
		verify(jobRepository, times(2)).save(any(LiveDownloadJob.class));
	}

	@Test
	void completesRemuxingWhenValidMp4Exists() throws Exception {
		String base = "live_20260811_remux_ok";
		Path mp4 = pathResolver.resolveMp4Path(base);
		Files.writeString(mp4, "fake-mp4-bytes");

		LiveDownloadJob remuxing = job(base, LiveJobStatus.REMUXING);
		remuxing.setFinalFilePath(mp4.toString());
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of(remuxing));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		assertThat(remuxing.getStatus()).isEqualTo(LiveJobStatus.COMPLETED);
		assertThat(remuxing.getDownloadedBytes()).isEqualTo(Files.size(mp4));
		assertThat(remuxing.getCompletedAt()).isNotNull();
		assertThat(remuxing.getErrorMessage()).isNull();
	}

	@Test
	void interruptsRemuxingWhenMp4Missing() {
		LiveDownloadJob remuxing = job("live_missing_mp4", LiveJobStatus.REMUXING);
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of(remuxing));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		assertThat(remuxing.getStatus()).isEqualTo(LiveJobStatus.INTERRUPTED);
		assertThat(remuxing.getErrorMessage()).isEqualTo(StartupJobRecoveryService.INTERRUPTED_MESSAGE);
	}

	@Test
	void interruptsRemuxingWhenMp4Empty() throws Exception {
		String base = "live_empty_mp4";
		Path mp4 = pathResolver.resolveMp4Path(base);
		Files.createFile(mp4);

		LiveDownloadJob remuxing = job(base, LiveJobStatus.REMUXING);
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of(remuxing));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		assertThat(remuxing.getStatus()).isEqualTo(LiveJobStatus.INTERRUPTED);
	}

	@Test
	void neverRestartsStreamUrls() {
		LiveDownloadJob recording = job("no_restart", LiveJobStatus.RECORDING);
		recording.setOriginalUrl("https://cdn.example.com/live.flv?token=expired");
		when(jobRepository.findByStatusIn(anyCollection())).thenReturn(List.of(recording));
		when(jobRepository.save(any(LiveDownloadJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		ArgumentCaptor<LiveDownloadJob> captor = ArgumentCaptor.forClass(LiveDownloadJob.class);
		verify(jobRepository).save(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(LiveJobStatus.INTERRUPTED);
		assertThat(captor.getValue().getOriginalUrl()).contains("token=expired");
	}

	private static LiveDownloadJob job(String baseName, LiveJobStatus status) {
		return LiveDownloadJob.builder()
				.id(UUID.randomUUID())
				.originalUrl("https://cdn.example.com/live.flv?token=abc")
				.outputBaseName(baseName)
				.status(status)
				.build();
	}
}
