package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.dto.response.RecordingJobResponse;
import com.vhmedia.livedownloader.entity.LiveDownloadJob;
import com.vhmedia.livedownloader.enums.LiveJobStatus;

final class RecordingJobMapper {

	private RecordingJobMapper() {
	}

	static RecordingJobResponse toResponse(LiveDownloadJob job) {
		boolean hasVideo = job.getVideoCodec() != null || job.getWidth() != null || job.getHeight() != null;
		boolean hasAudio = job.getAudioCodec() != null;

		return RecordingJobResponse.builder()
				.id(job.getId())
				.status(job.getStatus())
				.hasVideo(hasVideo)
				.hasAudio(hasAudio)
				.videoCodec(job.getVideoCodec())
				.audioCodec(job.getAudioCodec())
				.width(job.getWidth())
				.height(job.getHeight())
				.fps(job.getFps())
				.downloadedBytes(job.getDownloadedBytes())
				.durationMillis(job.getDurationMillis())
				.errorMessage(job.getErrorMessage())
				.outputBaseName(job.getOutputBaseName())
				.createdAt(job.getCreatedAt())
				.startedAt(job.getStartedAt())
				.stoppedAt(job.getStoppedAt())
				.completedAt(job.getCompletedAt())
				.updatedAt(job.getUpdatedAt())
				.build();
	}
}
