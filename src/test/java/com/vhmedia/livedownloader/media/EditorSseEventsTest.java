package com.vhmedia.livedownloader.media;

import com.vhmedia.livedownloader.enums.ExportStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditorSseEventsTest {

	@Test
	void mapsExportStatusToEditorExportEventNames() {
		assertThat(EditorSseEvents.forStatus(null)).isEqualTo(EditorSseEvents.STARTED);
		assertThat(EditorSseEvents.forStatus(ExportStatus.CREATED)).isEqualTo(EditorSseEvents.STARTED);
		assertThat(EditorSseEvents.forStatus(ExportStatus.PREPARING)).isEqualTo(EditorSseEvents.STARTED);
		assertThat(EditorSseEvents.forStatus(ExportStatus.RENDERING)).isEqualTo(EditorSseEvents.PROGRESS);
		assertThat(EditorSseEvents.forStatus(ExportStatus.FINALIZING)).isEqualTo(EditorSseEvents.FINALIZING);
		assertThat(EditorSseEvents.forStatus(ExportStatus.COMPLETED)).isEqualTo(EditorSseEvents.COMPLETED);
		assertThat(EditorSseEvents.forStatus(ExportStatus.FAILED)).isEqualTo(EditorSseEvents.FAILED);
		assertThat(EditorSseEvents.forStatus(ExportStatus.CANCELLED)).isEqualTo(EditorSseEvents.CANCELLED);

		assertThat(EditorSseEvents.STARTED).isEqualTo("editor.export.started");
		assertThat(EditorSseEvents.PROGRESS).isEqualTo("editor.export.progress");
		assertThat(EditorSseEvents.FINALIZING).isEqualTo("editor.export.finalizing");
		assertThat(EditorSseEvents.COMPLETED).isEqualTo("editor.export.completed");
		assertThat(EditorSseEvents.FAILED).isEqualTo("editor.export.failed");
		assertThat(EditorSseEvents.CANCELLED).isEqualTo("editor.export.cancelled");
	}
}
