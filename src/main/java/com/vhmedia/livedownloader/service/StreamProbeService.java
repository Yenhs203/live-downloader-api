package com.vhmedia.livedownloader.service;

import com.vhmedia.livedownloader.dto.StreamProbeResult;
import com.vhmedia.livedownloader.dto.response.StreamProbeResponse;
import com.vhmedia.livedownloader.exception.InvalidStreamUrlException;
import com.vhmedia.livedownloader.exception.MediaExecutableMissingException;
import com.vhmedia.livedownloader.exception.StreamProbeException;
import com.vhmedia.livedownloader.exception.StreamProbeTimeoutException;
import com.vhmedia.livedownloader.media.FfprobeService;
import com.vhmedia.livedownloader.media.MediaHttpFailureClassifier;
import com.vhmedia.livedownloader.media.MediaHttpFailureKind;
import com.vhmedia.livedownloader.util.StreamUrlValidator;
import com.vhmedia.livedownloader.util.UrlRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class StreamProbeService {

	private final StreamUrlValidator streamUrlValidator;
	private final FfprobeService ffprobeService;

	public StreamProbeService(StreamUrlValidator streamUrlValidator, FfprobeService ffprobeService) {
		this.streamUrlValidator = streamUrlValidator;
		this.ffprobeService = ffprobeService;
	}

	public StreamProbeResponse probe(String url) {
		String trimmedUrl = url == null ? null : url.trim();
		streamUrlValidator.validate(trimmedUrl);

		String redactedUrl = UrlRedactor.redact(trimmedUrl);
		try {
			StreamProbeResult result = ffprobeService.probe(trimmedUrl);
			return toResponse(result);
		} catch (InvalidStreamUrlException ex) {
			throw ex;
		} catch (MediaExecutableMissingException ex) {
			log.error(
					"Stream probe failed category={} url={} reason={}",
					MediaHttpFailureKind.EXECUTABLE_MISSING,
					redactedUrl,
					UrlRedactor.redactInText(ex.getMessage())
			);
			throw new MediaExecutableMissingException("Media tools (ffmpeg/ffprobe) are not available.");
		} catch (StreamProbeTimeoutException ex) {
			log.warn(
					"Stream probe timed out category={} url={} reason={}",
					MediaHttpFailureKind.TIMEOUT,
					redactedUrl,
					UrlRedactor.redactInText(ex.getMessage())
			);
			throw new StreamProbeTimeoutException("Stream probe timed out.");
		} catch (StreamProbeException ex) {
			MediaHttpFailureKind category = MediaHttpFailureClassifier.classify(ex.getMessage(), ex.getCause());
			log.warn(
					"Stream probe failed category={} url={} reason={}",
					category,
					redactedUrl,
					UrlRedactor.redactInText(ex.getMessage())
			);
			throw new StreamProbeException("Unable to read stream.");
		}
	}

	private static StreamProbeResponse toResponse(StreamProbeResult result) {
		StreamProbeResponse.VideoInfo video = null;
		if (result.isHasVideo()) {
			video = StreamProbeResponse.VideoInfo.builder()
					.codec(result.getVideoCodec())
					.width(result.getWidth())
					.height(result.getHeight())
					.fps(result.getFps())
					.build();
		}

		StreamProbeResponse.AudioInfo audio = null;
		if (result.isHasAudio()) {
			audio = StreamProbeResponse.AudioInfo.builder()
					.codec(result.getAudioCodec())
					.sampleRate(result.getAudioSampleRate())
					.channels(result.getAudioChannels())
					.build();
		}

		return StreamProbeResponse.builder()
				.valid(true)
				.hasVideo(result.isHasVideo())
				.hasAudio(result.isHasAudio())
				.format(result.getFormatName())
				.video(video)
				.audio(audio)
				.build();
	}
}
