package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vhmedia.livedownloader.enums.AssetType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EditorAssetResponse {

	UUID id;
	AssetType type;
	String originalFilename;
	String storageFileName;
	String mimeType;
	String contentType;
	long byteSize;
	Long durationMillis;
	Integer width;
	Integer height;
	String videoCodec;
	String audioCodec;
	boolean primarySource;
	Instant createdAt;
}
