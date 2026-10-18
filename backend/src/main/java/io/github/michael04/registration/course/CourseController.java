package io.github.michael04.registration.course;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Course search and course detail, endpoints 3 and 4 in docs/api-contract.md. */
@RestController
public class CourseController {

	private final CourseService service;

	public CourseController(CourseService service) {
		this.service = service;
	}

	@GetMapping("/api/terms")
	public CourseResponses.TermsResponse terms() {
		return service.terms();
	}

	@GetMapping("/api/courses")
	public CourseSearchResponse search(@RequestParam @NotBlank String term,
			@RequestParam(required = false) @Size(max = 100) String q,
			@RequestParam(required = false) @Size(max = 10) @Pattern(regexp = "[A-Za-z]+") String subject,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return service.search(term, q, subject, page, size);
	}

	@GetMapping("/api/courses/{courseId}")
	public CourseDetailResponse detail(@PathVariable @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Z]+-[0-9]+") String courseId,
			@RequestParam @NotBlank String term) {
		return service.detail(term, courseId);
	}

}
