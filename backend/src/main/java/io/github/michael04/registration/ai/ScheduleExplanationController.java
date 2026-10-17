package io.github.michael04.registration.ai;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
public class ScheduleExplanationController {
	private final ScheduleExplanationService service;
	public ScheduleExplanationController(ScheduleExplanationService service) { this.service = service; }

	@PostMapping("/api/schedules/explain")
	public ExplainScheduleResponse explain(@Valid @RequestBody ExplainScheduleRequest request) { return service.explain(request); }
}
