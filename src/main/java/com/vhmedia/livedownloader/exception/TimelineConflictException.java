package com.vhmedia.livedownloader.exception;

/**
 * Client {@code timelineVersion} does not match the project's current version, or JPA optimistic lock failed.
 */
public class TimelineConflictException extends ApiException {

	public TimelineConflictException(String message) {
		super(ErrorCode.TIMELINE_CONFLICT, message);
	}
}
