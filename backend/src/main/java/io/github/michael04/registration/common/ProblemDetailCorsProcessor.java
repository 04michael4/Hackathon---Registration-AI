package io.github.michael04.registration.common;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import tools.jackson.databind.ObjectMapper;

final class ProblemDetailCorsProcessor extends DefaultCorsProcessor {

	private final ObjectMapper objectMapper;

	ProblemDetailCorsProcessor(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean processRequest(@Nullable CorsConfiguration configuration, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		boolean accepted = super.processRequest(configuration, request, response);
		if (!accepted) {
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setHeader(ApiProblems.REQUEST_ID_HEADER, ApiProblems.requestId(request));
			objectMapper.writeValue(response.getOutputStream(),
				ApiProblems.create(HttpStatus.FORBIDDEN, "Cross-origin request is not allowed", request));
		}
		return accepted;
	}

	@Override
	protected void rejectRequest(ServerHttpResponse response) {
		// The caller writes the shared JSON body instead of the default plain-text body.
		response.setStatusCode(HttpStatus.FORBIDDEN);
	}

}
