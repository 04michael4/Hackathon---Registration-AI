package io.github.michael04.registration.demo;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.michael04.registration.course.CourseCatalog;
import jakarta.validation.Valid;

/**
 * Demo-only endpoints, present only with {@code CATALOG_SOURCE=demo}. They are additions, not changes: the shared
 * {@code /api/schedules/*} and {@code /api/courses} endpoints are untouched and also work on the demo catalog.
 */
@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "demo")
public class DemoController {

	private final DemoCatalog catalog;
	private final DemoScheduleService schedules;

	public DemoController(DemoCatalog catalog, DemoScheduleService schedules) {
		this.catalog = catalog;
		this.schedules = schedules;
	}

	public record OfferingsResponse(String term, String source, String caution, List<OfferingView> offerings) {
	}

	public record OfferingView(DemoFixture.Offering offering, String status, List<DemoWarning> warnings) {
	}

	/** Every offering with its sections and the warnings that apply to the (optional) profile given. */
	@GetMapping("/offerings")
	public OfferingsResponse offerings(@RequestParam(required = false) String classStanding, @RequestParam(required = false) String level,
			@RequestParam(required = false) String college, @RequestParam(required = false) List<String> majors) {
		DemoProfile profile = new DemoProfile(classStanding, level, college, majors);
		return new OfferingsResponse(catalog.term(), catalog.fixture().source(), catalog.fixture().caution(),
				catalog.offerings().stream().map(o -> {
					var report = DemoEligibility.report(o, profile);
					return new OfferingView(o, report.status(), report.warnings());
				}).toList());
	}

	@PostMapping("/schedules/generate")
	public DemoScheduleResponse generate(@Valid @RequestBody DemoGenerateRequest request) {
		return schedules.generate(request);
	}

}
