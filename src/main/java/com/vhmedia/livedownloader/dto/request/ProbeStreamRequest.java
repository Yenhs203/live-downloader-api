package com.vhmedia.livedownloader.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProbeStreamRequest {

	@NotBlank(message = "url must not be blank")
	@Size(max = 2048, message = "url must be at most 2048 characters")
	private String url;
}
