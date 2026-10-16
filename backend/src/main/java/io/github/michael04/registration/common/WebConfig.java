package io.github.michael04.registration.common;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class WebConfig {

	private final List<String> allowedOrigins;

	public WebConfig(@Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
			.map(String::trim)
			.filter(origin -> !origin.isEmpty())
			.distinct()
			.toList();
		this.allowedOrigins.forEach(WebConfig::validateOrigin);
	}

	@Bean
	public CorsFilter corsFilter(ObjectMapper objectMapper) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Accept", "Content-Type"));
		configuration.setExposedHeaders(List.of(ApiProblems.REQUEST_ID_HEADER));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		CorsFilter filter = new CorsFilter(source);
		filter.setCorsProcessor(new ProblemDetailCorsProcessor(objectMapper));
		return filter;
	}

	private static void validateOrigin(String origin) {
		URI uri;
		try {
			uri = URI.create(origin);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("CORS allowed origins must be exact HTTP(S) origins");
		}
		if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
				|| uri.getHost() == null || uri.getRawUserInfo() != null
				|| !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| uri.getPort() > 65535) {
			throw new IllegalArgumentException("CORS allowed origins must be exact HTTP(S) origins without paths or wildcards");
		}
	}

}
