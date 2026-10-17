package io.github.michael04.registration.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import io.github.michael04.registration.course.CourseCatalog;

/** Serves the one-page demo UI at {@code /demo}, only with {@code CATALOG_SOURCE=demo}. */
@Controller
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "demo")
public class DemoPageController {

	private final String html;

	public DemoPageController() throws IOException {
		try (InputStream in = DemoPageController.class.getResourceAsStream("/demo/index.html")) {
			if (in == null) {
				throw new IllegalStateException("demo/index.html is missing from the application resources");
			}
			this.html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@GetMapping(value = "/demo", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> page() {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(html);
	}

}
