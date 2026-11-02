package io.archsim.model.document;

import tools.jackson.databind.JsonNode;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;
import io.archsim.model.codec.ModelName;

/**
 * One value a scenario sets: at a dotted {@code path} inside a node's or edge's config,
 * or inside the workload, such as {@code replicas}, {@code serviceTimeMs.median} or
 * {@code pattern.burstRps}. In JSON (and the Python engine) it is an {@code Override}.
 *
 * @param target whose value it sets
 * @param path the dotted path to it
 * @param value the new value, any JSON; a copy goes in and comes out
 */
@ModelName("Override")
@Description("""
		Set one value. ``path`` is a dotted path inside the node/edge ``config`` or the workload,
		e.g. ``replicas``, ``serviceTimeMs.median``, ``pattern.burstRps``.""")
public record ScenarioOverride(OverrideTarget target,

		@MinLength(1) @MaxLength(200) @Matches(Patterns.OVERRIDE_PATH) String path,

		JsonNode value) {

	/**
	 * Copies the value, so the override does not change.
	 */
	public ScenarioOverride {
		value = value.deepCopy();
	}

	/**
	 * The new value, as a copy: a record does not change.
	 * @return a copy of the value
	 */
	@Override
	public JsonNode value() {
		return this.value.deepCopy();
	}
}
