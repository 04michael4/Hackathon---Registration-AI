package io.github.michael04.registration.schedule;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("scheduling")
public record SchedulingProperties(@Min(1) int nodeCap) {
}
