package io.github.michael04.registration.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		ProblemDetail problem = ApiProblems.create(status, ApiProblems.detail(status), servletRequest);
		if (ex instanceof MethodArgumentNotValidException validation) {
			addValidationErrors(problem, validation.getBindingResult().getAllErrors().stream()
				.map(error -> validationError(error, "$"))
				.toList());
		} else if (ex instanceof HandlerMethodValidationException validation && !validation.isForReturnValue()) {
			List<ValidationErrorResponse> errors = new ArrayList<>();
			for (var result : validation.getParameterValidationResults()) {
				String name = result.getMethodParameter().getParameterName();
				for (var error : result.getResolvableErrors()) {
					errors.add(validationError(error, name == null ? "$" : name));
				}
			}
			validation.getCrossParameterValidationResults()
				.forEach(error -> errors.add(validationError(error, "$")));
			addValidationErrors(problem, errors);
		}
		logServerError(ex, status, servletRequest);
		return super.handleExceptionInternal(ex, problem, ApiProblems.headers(headers, servletRequest), status, request);
	}

	@ExceptionHandler({NotFoundException.class, UnprocessableException.class, AiUnavailableException.class})
	public ResponseEntity<Object> handleApplicationException(RuntimeException ex, HttpServletRequest request) {
		HttpStatus status = ex instanceof NotFoundException ? HttpStatus.NOT_FOUND
			: ex instanceof UnprocessableException ? HttpStatus.valueOf(422) : HttpStatus.SERVICE_UNAVAILABLE;
		String detail = ex.getMessage() == null ? ApiProblems.detail(status) : ex.getMessage();
		logServerError(ex, status, request);
		return new ResponseEntity<>(ApiProblems.create(status, detail, request),
			ApiProblems.headers(HttpHeaders.EMPTY, request), status);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleUnexpectedException(Exception ex, HttpServletRequest request) {
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		logServerError(ex, status, request);
		return new ResponseEntity<>(ApiProblems.create(status, ApiProblems.detail(status), request),
			ApiProblems.headers(HttpHeaders.EMPTY, request), status);
	}

	private static ValidationErrorResponse validationError(MessageSourceResolvable error, String fallbackField) {
		String field = error instanceof FieldError fieldError ? fieldError.getField() : fallbackField;
		// Conversion messages can contain rejected input or internal Java type names.
		String message = error instanceof FieldError fieldError && fieldError.isBindingFailure()
			? "Invalid value" : error.getDefaultMessage();
		return new ValidationErrorResponse(field, message == null ? "Invalid value" : message);
	}

	private static void addValidationErrors(ProblemDetail problem, List<ValidationErrorResponse> errors) {
		problem.setDetail("Request validation failed");
		problem.setProperty("errors", errors.stream()
			.sorted(Comparator.comparing(ValidationErrorResponse::field).thenComparing(ValidationErrorResponse::message))
			.distinct()
			.toList());
	}

	private static void logServerError(Exception ex, HttpStatusCode status, HttpServletRequest request) {
		if (status.is5xxServerError()) {
			// Do not log exception messages/causes: they may contain credentials or student text.
			LOG.error("Request failed: requestId={}, status={}, exception={}",
				ApiProblems.requestId(request), status.value(), ex.getClass().getName());
		}
	}

}
