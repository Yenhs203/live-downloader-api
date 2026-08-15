package com.vhmedia.livedownloader.exception;

import com.vhmedia.livedownloader.dto.response.ErrorResponse;
import com.vhmedia.livedownloader.util.UrlRedactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
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

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(
			ConstraintViolationException ex,
			HttpServletRequest request
	) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
			String field = violation.getPropertyPath() == null ? "value" : violation.getPropertyPath().toString();
			fieldErrors.put(field, UrlRedactor.redactInText(violation.getMessage()));
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

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLock(
			ObjectOptimisticLockingFailureException ex,
			HttpServletRequest request
	) {
		ErrorCode errorCode = ErrorCode.TIMELINE_CONFLICT;
		log.warn("Optimistic lock path={}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
		return buildResponse(
				errorCode.getHttpStatus(),
				errorCode.name(),
				errorCode.getDefaultMessage(),
				request.getRequestURI(),
				null
		);
	}

	@ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
	public ResponseEntity<ErrorResponse> handleMultipartException(Exception ex, HttpServletRequest request) {
		String path = request.getRequestURI();
		ErrorCode errorCode = path != null && path.contains("/editor/")
				? ErrorCode.EDITOR_UPLOAD_TOO_LARGE
				: ErrorCode.UPLOAD_TOO_LARGE;
		log.warn("Upload rejected path={}: {}", path, ex.getClass().getSimpleName());
		return buildResponse(
				errorCode.getHttpStatus(),
				errorCode.name(),
				errorCode.getDefaultMessage(),
				path,
				null
		);
	}

	@ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<ErrorResponse> handleMissingPart(Exception ex, HttpServletRequest request) {
		ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
		log.warn("Missing request part/parameter path={}: {}", request.getRequestURI(), ex.getMessage());
		return buildResponse(
				errorCode.getHttpStatus(),
				errorCode.name(),
				errorCode.getDefaultMessage(),
				request.getRequestURI(),
				null
		);
	}

	/**
	 * HTML5 video Range seeks abort unused byte ranges. Do not write JSON onto a committed {@code video/mp4} response.
	 */
	@ExceptionHandler({AsyncRequestNotUsableException.class, HttpMessageNotWritableException.class})
	public void handleUnusableResponse(Exception ex, HttpServletRequest request) {
		if (isClientDisconnected(ex) || ex instanceof HttpMessageNotWritableException) {
			log.debug("Client closed media stream path={}", request.getRequestURI());
			return;
		}
		log.warn("Response not writable path={}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(
			Exception ex,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		if (isClientDisconnected(ex) || (response != null && response.isCommitted())) {
			log.debug("Client closed connection path={}", request.getRequestURI());
			return null;
		}
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

	static boolean isClientDisconnected(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			String name = current.getClass().getName();
			if (current instanceof AsyncRequestNotUsableException
					|| name.endsWith("ClientAbortException")
					|| name.endsWith("AsyncRequestNotUsableException")) {
				return true;
			}
			if (current instanceof IOException) {
				String message = current.getMessage();
				if (message != null) {
					String lower = message.toLowerCase(Locale.ROOT);
					if (lower.contains("connection was aborted")
							|| lower.contains("broken pipe")
							|| lower.contains("connection reset")
							|| lower.contains("an established connection was aborted")) {
						return true;
					}
				}
			}
			current = current.getCause();
		}
		return false;
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
			case INVALID_STREAM_URL, INVALID_RECORDING_STATE, CONCURRENT_LIMIT_EXCEEDED, RECORDING_NOT_FOUND,
				 EDITOR_PROJECT_NOT_FOUND, EDITOR_ASSET_NOT_FOUND, EDITOR_SEGMENT_NOT_FOUND,
				 INVALID_EDITOR_FILE, EDITOR_UPLOAD_TOO_LARGE, EDITOR_PROBE_FAILED,
				 INVALID_SPLIT_POSITION, INVALID_SEGMENT_BOUNDARY, SEGMENT_TOO_SHORT, INVALID_SEGMENT_TRIM,
				 SEGMENTS_NOT_MERGEABLE, INVALID_PLAYBACK_RATE, PLAYBACK_RATE_NOT_SUPPORTED_FOR_IMAGE,
				 INVALID_OUTPUT_DURATION,
				 OUTPUT_DURATION_EXCEEDS_AUDIO, INVALID_TIMELINE, TIMELINE_CONFLICT, INVALID_EDITOR_STATE,
				 INVALID_EDITOR_EXPORT,
				 EXPORT_ALREADY_RUNNING, EXPORT_NOT_FOUND, EXPORT_NOT_READY,
				 CONCURRENT_EDITOR_LIMIT_EXCEEDED, UPLOAD_TOO_LARGE ->
					ex.getMessage() != null && !ex.getMessage().isBlank()
							? ex.getMessage()
							: errorCode.getDefaultMessage();
			case STREAM_PROBE_FAILED, STREAM_PROBE_TIMEOUT, MEDIA_EXECUTABLE_MISSING,
				 FFMPEG_START_FAILED, FFMPEG_EXECUTION_FAILED, REMUX_FAILED, STORAGE_ERROR,
				 EDITOR_STORAGE_ERROR, EXPORT_FAILED, VALIDATION_FAILED, INTERNAL_ERROR ->
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
