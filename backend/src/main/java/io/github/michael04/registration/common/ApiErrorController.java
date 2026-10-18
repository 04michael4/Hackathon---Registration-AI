package io.github.michael04.registration.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Covers servlet error dispatches that never reach controller advice. */
@RestController
public class ApiErrorController implements ErrorController {

	@RequestMapping("/error")
	public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		int statusCode = code instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
		HttpStatusCode status = HttpStatusCode.valueOf(statusCode);
		return new ResponseEntity<>(ApiProblems.create(status, ApiProblems.detail(status), request),
			ApiProblems.headers(HttpHeaders.EMPTY, request), status);
	}

}
