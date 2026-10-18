package io.github.michael04.registration.schedule;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
	private final ScheduleService service;

	public ScheduleController(ScheduleService service) {
		this.service = service;
	}

	@PostMapping("/generate")
	public GenerateScheduleResponse generate(@Valid @RequestBody GenerateScheduleRequest request) {
		return service.generate(request);
	}

	@PostMapping("/check")
	public CheckScheduleResponse check(@Valid @RequestBody CheckScheduleRequest request) {
		return service.check(request);
	}

	@PostMapping("/suggest-electives")
	public SuggestElectivesResponse suggestElectives(@Valid @RequestBody SuggestElectivesRequest request) {
		return service.suggestElectives(request);
	}
}
