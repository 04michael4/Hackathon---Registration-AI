package io.github.michael04.registration.ai;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** {@code POST /api/preferences/extract}: see docs/api-contract.md, endpoint 5. */
@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

	private final PreferenceExtractionService service;

	public PreferenceController(PreferenceExtractionService service) {
		this.service = service;
	}

	@PostMapping("/extract")
	public ExtractPreferencesResponse extract(@Valid @RequestBody ExtractPreferencesRequest request) {
		return service.extract(request.term(), request.text());
	}

}
