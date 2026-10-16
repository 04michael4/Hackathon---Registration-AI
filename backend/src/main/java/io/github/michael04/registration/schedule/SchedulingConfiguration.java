package io.github.michael04.registration.schedule;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SchedulingProperties.class)
public class SchedulingConfiguration {
}
