package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
class EditorStartupRecoveryServiceTest {

	@Mock
	private VideoExportJobRepository exportJobRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	private EditorStartupRecoveryService service;

	@BeforeEach
	void setUp() {
		lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new EditorStartupRecoveryService(exportJobRepository, transactionManager);
	}

	@Test
	void doesNothingWhenNoActiveExportJobs() {
		when(exportJobRepository.findByStatusIn(anyCollection())).thenReturn(List.of());

		service.recoverOnStartup();

		verify(exportJobRepository, never()).save(any());
	}

	@Test
	void marksActiveExportJobsFailedWithoutUsingRecordingStatuses() {
		VideoExportJob created = job(ExportStatus.CREATED);
		VideoExportJob preparing = job(ExportStatus.PREPARING);
		VideoExportJob rendering = job(ExportStatus.RENDERING);
		VideoExportJob finalizing = job(ExportStatus.FINALIZING);
		when(exportJobRepository.findByStatusIn(anyCollection()))
				.thenReturn(List.of(created, preparing, rendering, finalizing));
		when(exportJobRepository.save(any(VideoExportJob.class))).thenAnswer(inv -> inv.getArgument(0));

		service.recoverOnStartup();

		assertThat(created.getStatus()).isEqualTo(ExportStatus.FAILED);
		assertThat(preparing.getStatus()).isEqualTo(ExportStatus.FAILED);
		assertThat(rendering.getStatus()).isEqualTo(ExportStatus.FAILED);
		assertThat(finalizing.getStatus()).isEqualTo(ExportStatus.FAILED);
		assertThat(preparing.getErrorMessage()).isEqualTo(EditorStartupRecoveryService.FAILED_MESSAGE);
		assertThat(rendering.getCompletedAt()).isNotNull();
		verify(exportJobRepository, times(4)).save(any(VideoExportJob.class));
	}

	private static VideoExportJob job(ExportStatus status) {
		return VideoExportJob.builder()
				.id(UUID.randomUUID())
				.projectId(UUID.randomUUID())
				.status(status)
				.fpsPreset("ORIGINAL")
				.resolution("ORIGINAL")
				.videoCodec("H264")
				.build();
	}
}
