package io.github.michael04.registration.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.health.HealthController;

@WebMvcTest({HealthController.class, ApiErrorController.class})
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@CsvSource({
		"not-found, 404, Course not found",
		"unprocessable, 422, Credit range is invalid",
		"ai-unavailable, 503, AI service is unavailable",
		"upstream, 502, Upstream service returned an invalid response",
		"rate-limit, 429, Too many requests",
		"unexpected, 500, An unexpected error occurred",
		"illegal-argument, 500, An unexpected error occurred",
		"return-value, 500, An unexpected error occurred"
	})
	void applicationErrorsUseSafeProblemDetails(String path, int code, String detail) throws Exception {
		String uri = "/api/test/" + path;
		assertProblem(mockMvc.perform(get(uri).queryParam("private", "sensitive-test-input")), code, detail, uri)
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andExpect(content().string(not(containsString("sensitive-test-input"))));
	}

	@Test
	void validationErrorsIncludeSortedNestedFieldPaths() throws Exception {
		assertProblem(mockMvc.perform(post("/api/test/validate")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"name":"", "credits":22, "preferences":{"term":""}}
				""")), 400, "Request validation failed", "/api/test/validate")
			.andExpect(jsonPath("$.errors.length()").value(3))
			.andExpect(jsonPath("$.errors[0].field").value("credits"))
			.andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 21"))
			.andExpect(jsonPath("$.errors[1].field").value("name"))
			.andExpect(jsonPath("$.errors[2].field").value("preferences.term"))
			.andExpect(jsonPath("$.errors[2].message").value("must not be blank"))
			.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
	}

	@Test
	void validBodyStillReachesTheController() throws Exception {
		mockMvc.perform(post("/api/test/validate").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"name":"Example", "credits":18, "preferences":{"term":"sample"}}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.credits").value(18));
	}

	@Test
	void malformedJsonDoesNotExposeParserDetails() throws Exception {
		assertProblem(mockMvc.perform(post("/api/test/validate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": sensitive-test-input")), 400, "Request is invalid", "/api/test/validate")
			.andExpect(content().string(not(containsString("sensitive-test-input"))));
	}

	@Test
	void missingBodyUsesProblemDetails() throws Exception {
		assertProblem(mockMvc.perform(post("/api/test/validate").contentType(MediaType.APPLICATION_JSON)),
			400, "Request is invalid", "/api/test/validate");
	}

	@Test
	void invalidQueryParameterUsesFieldErrors() throws Exception {
		assertProblem(mockMvc.perform(get("/api/test/parameter").param("page", "0")),
			400, "Request validation failed", "/api/test/parameter")
			.andExpect(jsonPath("$.errors[0].field").value("page"))
			.andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
	}

	@Test
	void missingQueryParameterUsesProblemDetails() throws Exception {
		assertProblem(mockMvc.perform(get("/api/test/parameter")), 400, "Request is invalid", "/api/test/parameter");
	}

	@Test
	void queryTypeMismatchDoesNotEchoRejectedValue() throws Exception {
		assertProblem(mockMvc.perform(get("/api/test/parameter").param("page", "sensitive-test-input")),
			400, "Request is invalid", "/api/test/parameter")
			.andExpect(content().string(not(containsString("sensitive-test-input"))));
	}

	@Test
	void unknownRouteUsesProblemDetails() throws Exception {
		assertProblem(mockMvc.perform(get("/api/missing")), 404, "Resource not found", "/api/missing");
	}

	@Test
	void wrongMethodPreservesAllowHeader() throws Exception {
		assertProblem(mockMvc.perform(post("/api/health")), 405,
			"HTTP method is not supported for this resource", "/api/health")
			.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
	}

	@Test
	void unsupportedContentTypeUsesProblemDetails() throws Exception {
		assertProblem(mockMvc.perform(post("/api/test/validate").contentType(MediaType.TEXT_PLAIN).content("input")),
			415, "Request content type is not supported", "/api/test/validate");
	}

	@Test
	void unsupportedAcceptTypeUsesProblemDetails() throws Exception {
		assertProblem(mockMvc.perform(get("/api/health").accept(MediaType.APPLICATION_XML)),
			406, "Requested response media type is not supported", "/api/health");
	}

	@Test
	void requestIdIsGeneratedAndMatchesTheResponseHeader() throws Exception {
		var result = mockMvc.perform(get("/api/test/unexpected").header("X-Request-Id", "untrusted-id"))
			.andExpect(status().isInternalServerError())
			.andReturn();
		String id = result.getResponse().getHeader("X-Request-Id");
		assertThat(UUID.fromString(id).toString()).isEqualTo(id);
		assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + id + "\"")
			.doesNotContain("untrusted-id");
	}

	@Test
	void servletErrorDispatchUsesOriginalPathAndHidesErrorAttributes() throws Exception {
		assertProblem(mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML)
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
			.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/example")
			.requestAttr(RequestDispatcher.ERROR_MESSAGE, "sensitive-test-input")
			.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new RuntimeException("sensitive-test-input"))),
			500, "An unexpected error occurred", "/api/example")
			.andExpect(content().string(not(containsString("sensitive-test-input"))));
	}

	private ResultActions assertProblem(ResultActions result, int code, String detail, String instance) throws Exception {
		return result.andExpect(status().is(code))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.title").value(HttpStatus.valueOf(code).getReasonPhrase()))
			.andExpect(jsonPath("$.status").value(code))
			.andExpect(jsonPath("$.detail").value(detail))
			.andExpect(jsonPath("$.instance").value(instance))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.exception").doesNotExist())
			.andExpect(jsonPath("$.message").doesNotExist());
	}

	@RestController
	@RequestMapping("/api/test")
	static class TestController {

		@GetMapping("/not-found")
		void notFound() {
			throw new NotFoundException("Course not found");
		}

		@GetMapping("/unprocessable")
		void unprocessable() {
			throw new UnprocessableException("Credit range is invalid");
		}

		@GetMapping("/ai-unavailable")
		void aiUnavailable() {
			throw new AiUnavailableException(new RuntimeException("sensitive-test-input"));
		}

		@GetMapping("/upstream")
		void upstream() {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sensitive-test-input");
		}

		@GetMapping("/rate-limit")
		void rateLimit() {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "sensitive-test-input");
		}

		@GetMapping("/unexpected")
		void unexpected() {
			throw new IllegalStateException("sensitive-test-input");
		}

		@GetMapping("/illegal-argument")
		void illegalArgument() {
			throw new IllegalArgumentException("sensitive-test-input");
		}

		@GetMapping("/return-value")
		@NotBlank
		String returnValue() {
			return "";
		}

		@GetMapping("/parameter")
		int parameter(@RequestParam @Min(1) int page) {
			return page;
		}

		@PostMapping("/validate")
		TestRequest validate(@Valid @RequestBody TestRequest request) {
			return request;
		}

	}

	record TestRequest(@NotBlank String name, @Min(1) @Max(21) int credits, @Valid NestedRequest preferences) {
	}

	record NestedRequest(@NotBlank String term) {
	}

}
