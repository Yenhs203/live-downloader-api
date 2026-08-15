package com.vhmedia.livedownloader.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vhmedia.livedownloader.enums.ExportStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EditorEventResponseTest {

	@Test
	void jsonUsesExportProgressFieldNames() throws Exception {
		UUID exportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID projectId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		EditorEventResponse payload = EditorEventResponse.builder()
				.exportId(exportId)
				.projectId(projectId)
				.status(ExportStatus.RENDERING)
				.processedMillis(23_000L)
				.durationMillis(60_000L)
				.progressPercent(38.33d)
				.fps(42.1d)
				.speed(1.7d)
				.build();

		JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(payload));

		assertThat(json.get("exportId").asText()).isEqualTo(exportId.toString());
		assertThat(json.get("projectId").asText()).isEqualTo(projectId.toString());
		assertThat(json.get("status").asText()).isEqualTo("RENDERING");
		assertThat(json.get("processedMillis").asLong()).isEqualTo(23_000L);
		assertThat(json.get("durationMillis").asLong()).isEqualTo(60_000L);
		assertThat(json.get("progressPercent").asDouble()).isEqualTo(38.33d);
		assertThat(json.get("fps").asDouble()).isEqualTo(42.1d);
		assertThat(json.get("speed").asDouble()).isEqualTo(1.7d);
		assertThat(json.has("exportJobId")).isFalse();
		assertThat(json.has("totalMillis")).isFalse();
	}
}
