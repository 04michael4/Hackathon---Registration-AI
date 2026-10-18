package io.github.michael04.registration.ai;

import org.springframework.stereotype.Service;

import io.github.michael04.registration.course.CourseCatalog;

/** Free text → validated preferences: check the term, ask the extractor, then validate in Java. */
@Service
public class PreferenceExtractionService {

	private final PreferenceExtractor extractor;

	private final CourseCatalog catalog;

	private final PreferenceAssembler assembler;

	public PreferenceExtractionService(PreferenceExtractor extractor, CourseCatalog catalog, PreferenceAssembler assembler) {
		this.extractor = extractor;
		this.catalog = catalog;
		this.assembler = assembler;
	}

	public ExtractPreferencesResponse extract(String term, String text) {
		// Check the term first so a bad request never costs an AI call.
		if (!catalog.termExists(term)) {
			throw new UnknownTermException(term);
		}
		RawPreferences raw = extractor.extract(text);
		if (raw == null) {
			throw new AiOutputInvalidException("AI returned no preferences");
		}
		return assembler.assemble(term, raw);
	}

}
