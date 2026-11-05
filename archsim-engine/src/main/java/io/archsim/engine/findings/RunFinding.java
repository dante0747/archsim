package io.archsim.engine.findings;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.codec.RecordCodec;

/**
 * Something a run found, with the numbers it rests on: nothing here is invented.
 *
 * @param severity how much it matters
 * @param kind what it is about
 * @param nodeId the part it is about, if one
 * @param title what it is, in a line
 * @param detail what the run measured, in words
 * @param suggestion what to do about it, if anything
 * @param evidence the figures it rests on, by name; which depends on its kind
 */
public record RunFinding(FindingSeverity severity, FindingKind kind, @Nullable String nodeId, String title,
		String detail, @OmitWhenDefault @Nullable String suggestion, ObjectNode evidence) {

	/**
	 * Copies the evidence, so the finding does not change.
	 */
	public RunFinding {
		evidence = Objects.requireNonNull(evidence, "a finding has its evidence").deepCopy();
	}

	/**
	 * The figures it rests on.
	 * @return a copy of the evidence
	 */
	@Override
	public ObjectNode evidence() {
		return this.evidence.deepCopy();
	}

	/**
	 * An empty set of evidence, to put the figures in.
	 * @return the evidence
	 */
	static ObjectNode emptyEvidence() {
		return JsonNodeFactory.instance.objectNode();
	}

	/**
	 * The finding as a report writes it.
	 * @return its JSON
	 */
	public ObjectNode toJson() {
		return RecordCodec.write(this);
	}

}
