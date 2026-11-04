package io.archsim.engine.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.sizing.PartSize;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Scenario;

/** The document a run simulates, and how it was resolved. */
public final class ResolvedDesign {

	private final ArchitectureDocument document;

	private final @Nullable Scenario scenario;

	private final Map<String, PartSize> sizes;

	ResolvedDesign(ArchitectureDocument document, @Nullable Scenario scenario, Map<String, PartSize> sizes) {
		this.document = document;
		this.scenario = scenario;
		this.sizes = Collections.unmodifiableMap(new LinkedHashMap<>(sizes));
	}

	/**
	 * The resolved document: defaults in full, prices pinned, specs auto-sized, the
	 * scenario applied; no scenarios or layout of its own.
	 * @return the document
	 */
	public ArchitectureDocument document() {
		return this.document;
	}

	/**
	 * The scenario applied.
	 * @return the scenario; empty for the design itself
	 */
	public Optional<Scenario> scenario() {
		return Optional.ofNullable(this.scenario);
	}

	/**
	 * The auto size of each part, from the design as drawn.
	 * @return the sizes, by part id, in the design's order
	 */
	public Map<String, PartSize> sizes() {
		return this.sizes;
	}

}
