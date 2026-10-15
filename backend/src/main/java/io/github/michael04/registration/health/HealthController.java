package io.github.michael04.registration.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness check used by the frontend, teammates, and (later) deployment tooling.
 * Intentionally has no dependencies on the database or external services.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

	private final String serviceName;

	public HealthController(@Value("${spring.application.name}") String serviceName) {
		this.serviceName = serviceName;
	}

	@GetMapping("/health")
	public HealthResponse health() {
		return new HealthResponse("UP", serviceName);
	}

}
