package io.github.michael04.registration.course.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The explicit map from an API term ({@code 2027-SPRING}) to a Banner term code ({@code 202710}).
 *
 * <p>It is a lookup, never a formula: other Banner suffixes are unverified. The API never accepts a Banner code as a
 * term, so {@code 202710} itself is an unknown term.
 */
final class TermMapping {

	private static final Pattern BANNER_CODE = Pattern.compile("\\d{6}");

	private final Map<String, String> apiToBanner;

	private TermMapping(Map<String, String> apiToBanner) {
		this.apiToBanner = apiToBanner;
	}

	/** Fails fast, with a message that names the setting, if the configuration is empty or malformed. */
	static TermMapping of(Map<String, String> configured) {
		if (configured == null || configured.isEmpty()) {
			throw new IllegalStateException("app.catalog.terms must map at least one API term to its Banner code, "
					+ "for example app.catalog.terms[2027-SPRING]=202710");
		}
		Map<String, String> mapping = new TreeMap<>();
		configured.forEach((api, banner) -> {
			String apiTerm = api == null ? "" : api.trim();
			String bannerCode = banner == null ? "" : banner.trim();
			if (apiTerm.isEmpty()) {
				throw new IllegalStateException("app.catalog.terms contains a blank API term");
			}
			if (BANNER_CODE.matcher(apiTerm).matches()) {
				throw new IllegalStateException("app.catalog.terms: API term '" + apiTerm
						+ "' looks like a Banner code; use a name such as 2027-SPRING");
			}
			if (!BANNER_CODE.matcher(bannerCode).matches()) {
				throw new IllegalStateException("app.catalog.terms[" + apiTerm + "] must be a 6-digit Banner term code");
			}
			mapping.put(apiTerm, bannerCode);
		});
		if (mapping.values().stream().distinct().count() != mapping.size()) {
			throw new IllegalStateException("app.catalog.terms maps two API terms to the same Banner code");
		}
		return new TermMapping(Map.copyOf(mapping));
	}

	/** The Banner code for an API term, or empty if the term isn't configured. */
	Optional<String> banner(String apiTerm) {
		return apiTerm == null ? Optional.empty() : Optional.ofNullable(apiToBanner.get(apiTerm.trim()));
	}

	Set<String> apiTerms() {
		return new java.util.TreeSet<>(apiToBanner.keySet());
	}

}
