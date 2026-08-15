package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.entity.VideoExportJob;
import com.vhmedia.livedownloader.enums.ExportStatus;
import com.vhmedia.livedownloader.repository.VideoExportJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * FFmpeg export processes do not survive JVM restart. Active export jobs are marked {@code FAILED}.
 */
@Slf4j
@Service
public class EditorStartupRecoveryService {

	static final String FAILED_MESSAGE =
			"Export failed because the application restarted; in-memory FFmpeg process was lost";

	private final VideoExportJobRepository exportJobRepository;
	private final TransactionTemplate transactionTemplate;

	public EditorStartupRecoveryService(
			VideoExportJobRepository exportJobRepository,
			PlatformTransactionManager transactionManager
	) {
		this.exportJobRepository = exportJobRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		transactionTemplate.executeWithoutResult(status -> doRecover());
	}

	void doRecover() {
		List<VideoExportJob> stranded = exportJobRepository.findByStatusIn(List.of(
				ExportStatus.CREATED,
				ExportStatus.PREPARING,
				ExportStatus.RENDERING,
				ExportStatus.FINALIZING
		));
		if (stranded.isEmpty()) {
			log.info("Editor startup recovery: no active export jobs to recover");
			return;
		}
		Instant now = Instant.now();
		for (VideoExportJob job : stranded) {
			job.setStatus(ExportStatus.FAILED);
			job.setErrorMessage(FAILED_MESSAGE);
			job.setCompletedAt(now);
			exportJobRepository.save(job);
			log.warn("Editor startup recovery: marked FAILED exportJobId={} projectId={}", job.getId(), job.getProjectId());
		}
	}
}
