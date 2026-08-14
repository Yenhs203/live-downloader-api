package com.vhmedia.livedownloader.exception;

import com.vhmedia.livedownloader.dto.response.ErrorResponse;
import com.vhmedia.livedownloader.util.UrlRedactor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain exceptions to a consistent RFC-style JSON error body.
 * Never returns stack traces or sensitive URL query parameters to clients.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
		ErrorCode errorCode = ex.getErrorCode();
		String clientMessage = clientSafeMessage(ex);
		logApiException(ex, request.getRequestURI());
		return buildResponse(errorCode.getHttpStatus(), errorCode.name(), clientMessage, request.getRequestURI(), null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
			MethodArgumentNotValidException ex,
			HttpServletRequest request
	) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.put(fieldError.getField(), UrlRedactor.redactInText(fieldError.getDefaultMessage()));
		}

		ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
		log.warn("Validation failed path={} fields={}", request.getRequestURI(), fieldErrors.keySet());
		return buildResponse(
				errorCode.getHttpStatus(),
				errorCode.name(),
				errorCode.getDefaultMessage(),
				request.getRequestURI(),
				fieldErrors
		);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex, HttpServletRequest request) {
		log.error("Unexpected error path={}", request.getRequestURI(), ex);
		ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
		return buildResponse(
				errorCode.getHttpStatus(),
				errorCode.name(),
				errorCode.getDefaultMessage(),
				request.getRequestURI(),
				null
		);
	}

	private static void logApiException(ApiException ex, String path) {
		ErrorCode errorCode = ex.getErrorCode();
		String redacted = UrlRedactor.redactInText(ex.getMessage());
		if (errorCode.getHttpStatus().is5xxServerError()) {
			log.error("API exception [{}] path={}: {}", errorCode.name(), path, redacted, ex);
		} else if (ex.getCause() != null) {
			log.warn("API exception [{}] path={}: {}", errorCode.name(), path, redacted, ex);
		} else {
			log.warn("API exception [{}] path={}: {}", errorCode.name(), path, redacted);
		}
	}

	/**
	 * Prefer stable client-facing messages; never leak ffmpeg/ffprobe stderr or URL tokens.
	 */
	private static String clientSafeMessage(ApiException ex) {
		ErrorCode errorCode = ex.getErrorCode();
		String raw = switch (errorCode) {
			case INVALID_STREAM_URL, INVALID_RECORDING_STATE, CONCURRENT_LIMIT_EXCEEDED, RECORDING_NOT_FOUND ->
					ex.getMessage() != null && !ex.getMessage().isBlank()
							? ex.getMessage()
							: errorCode.getDefaultMessage();
			case STREAM_PROBE_FAILED, STREAM_PROBE_TIMEOUT, MEDIA_EXECUTABLE_MISSING,
				 FFMPEG_START_FAILED, FFMPEG_EXECUTION_FAILED, REMUX_FAILED, STORAGE_ERROR,
				 VALIDATION_FAILED, INTERNAL_ERROR ->
					errorCode.getDefaultMessage();
		};
		return UrlRedactor.redactInText(raw);
	}

	private ResponseEntity<ErrorResponse> buildResponse(
			HttpStatus status,
			String code,
			String message,
			String path,
			Map<String, String> fieldErrors
	) {
		ErrorResponse body = ErrorResponse.builder()
				.timestamp(Instant.now())
				.status(status.value())
				.code(code)
				.message(message)
				.path(path)
				.fieldErrors(fieldErrors)
				.build();
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body);
	}
}
