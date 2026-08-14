package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.enums.LiveJobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingSseEventsTest {

	@Test
	void mapsStatusesToNamedEvents() {
		assertThat(RecordingSseEvents.STARTED).isEqualTo("recording.started");
		assertThat(RecordingSseEvents.PROGRESS).isEqualTo("recording.progress");
		assertThat(RecordingSseEvents.STOPPING).isEqualTo("recording.stopping");
		assertThat(RecordingSseEvents.REMUXING).isEqualTo("recording.remuxing");
		assertThat(RecordingSseEvents.COMPLETED).isEqualTo("recording.completed");
		assertThat(RecordingSseEvents.FAILED).isEqualTo("recording.failed");

		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.RECORDING)).isEqualTo("recording.progress");
		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.STOPPING)).isEqualTo("recording.stopping");
		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.REMUXING)).isEqualTo("recording.remuxing");
		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.COMPLETED)).isEqualTo("recording.completed");
		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.FAILED)).isEqualTo("recording.failed");
		assertThat(RecordingSseEvents.forStatus(LiveJobStatus.INTERRUPTED)).isEqualTo("recording.failed");
	}
}
