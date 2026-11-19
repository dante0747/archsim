package io.archsim.benchmarks.provenance;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.values.PythonValues;

/**
 * What freezing the Java engine as it is would record: the hash over its source trees,
 * which {@code engines.json} keeps beside the freeze's date and tag, and by which the
 * held-out experiments' seal knows the frozen engine again (ADR-0088). The harness prints
 * it; the freeze itself, a tag and an entry of {@code engines.json}, is a person's. A
 * freeze is never edited: a version already frozen keeps its sources, and an engine that
 * changed gets a new version.
 */
public final class EngineFreeze {

	private static final String UNKNOWN = "unknown";

	private static final String TAG = "tag";

	private static final int SHORT_ID = 12;

	private final Engines engines;

	/**
	 * Freezes against the engines already listed.
	 * @param engines {@code engines.json}'s engines
	 */
	public EngineFreeze(Engines engines) {
		this.engines = engines;
	}

	/**
	 * What a freeze of the engine that would run records, or why it is not to be frozen
	 * now.
	 * @param version the engine's version
	 * @param provenance what would run ({@link Provenance})
	 * @param now the time, as {@code engines.json} writes it
	 * @return the lines to print
	 */
	public List<String> lines(String version, JsonNode provenance, String now) {
		String sources = text(PythonValues.get(provenance, Engines.SOURCES_KEY));
		String identity = "engine " + version + ": sources " + sources + ", commit "
				+ text(PythonValues.get(provenance, "commit"));
		Optional<JsonNode> frozen = frozen(version);
		if (frozen.isPresent()) {
			return List.of(identity, alreadyFrozen(version, frozen.get(), sources));
		}
		JsonNode dirty = PythonValues.get(provenance, "dirty");
		if (!dirty.isBoolean() || dirty.booleanValue()) {
			return List.of(identity, "the engine's sources or the harness have uncommitted changes (or git cannot "
					+ "tell): commit them first, so that the sources named are the ones that run");
		}
		String tag = "engine-" + version;
		JsonNode entry = JsonNodeFactory.instance.objectNode()
			.put(Engines.SOURCES_KEY, sources)
			.put("harnessHash", text(PythonValues.get(provenance, "harnessHash")))
			.put("frozenAt", now)
			.put(TAG, tag);
		return List.of(identity, "to freeze it: tag this commit " + tag + ", push the tag, and add to version "
				+ version + "'s entry in benchmarks/engines.json: " + entry);
	}

	// the version's entry, when it is frozen with the Java engine's sources
	private Optional<JsonNode> frozen(String version) {
		return this.engines.frozen()
			.stream()
			.filter((engine) -> version.equals(engine.path("version").stringValue(""))
					&& engine.path(Engines.SOURCES_KEY).isString())
			.findFirst();
	}

	private static String alreadyFrozen(String version, JsonNode entry, String sources) {
		String tag = text(PythonValues.get(entry, TAG));
		String recorded = entry.get(Engines.SOURCES_KEY).stringValue();
		if (recorded.equals(sources)) {
			return "version " + version + " is frozen with these sources (tag " + tag + ")";
		}
		return "version " + version + " is already frozen with other sources ("
				+ recorded.substring(0, Math.min(SHORT_ID, recorded.length())) + ", tag " + tag
				+ "), and a freeze is never edited: run from that tag's sources, or give the engine a new version "
				+ "and freeze that";
	}

	// a hash as text; what git could not name reads unknown
	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : UNKNOWN;
	}

}
