package io.github.michael04.registration.common;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Shared rendering for MVC exceptions, servlet error dispatches, and CORS rejections. */
final class ApiProblems {

	static final String REQUEST_ID_HEADER = "X-Request-Id";
	private static final String REQUEST_ID_ATTRIBUTE = ApiProblems.class.getName() + ".requestId";

	private ApiProblems() {
	}

	static ProblemDetail create(HttpStatusCode status, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String originalPath
			? originalPath : request.getRequestURI();
		problem.setInstance(URI.create(path));
		problem.setProperty("requestId", requestId(request));
		return problem;
	}

	static String requestId(HttpServletRequest request) {
		if (request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String id) {
			return id;
		}
		String id = UUID.randomUUID().toString();
		request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
		return id;
	}

	static HttpHeaders headers(HttpHeaders original, HttpServletRequest request) {
		HttpHeaders headers = new HttpHeaders();
		headers.addAll(original);
		headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		headers.set(REQUEST_ID_HEADER, requestId(request));
		return headers;
	}

	static String detail(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "Request is invalid";
			case 403 -> "Request is not allowed";
			case 404 -> "Resource not found";
			case 405 -> "HTTP method is not supported for this resource";
			case 406 -> "Requested response media type is not supported";
			case 413 -> "Request body is too large";
			case 415 -> "Request content type is not supported";
			case 422 -> "Request cannot be processed";
			case 429 -> "Too many requests";
			case 502 -> "Upstream service returned an invalid response";
			case 503 -> "Service is temporarily unavailable";
			case 504 -> "Upstream service timed out";
			default -> status.is5xxServerError() ? "An unexpected error occurred" : "Request could not be processed";
		};
	}

}
