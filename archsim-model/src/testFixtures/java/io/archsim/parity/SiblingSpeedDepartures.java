package io.archsim.parity;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.Resources;

/**
 * Where the Java's output departs from the frozen engine's because schema 1.11 counts a
 * vCPU of a service, database or cache as a hyperthread unless the part, the design, its
 * instance family or its platform says otherwise (ADR-0091), where the frozen engine's
 * vCPUs were whole cores unless a part set its sibling speed, and how it is read as the
 * frozen engine's all the same.
 * <ul>
 * <li>A document the frozen engine was given, at schema 1.7, is given to the Java pinned
 * to whole cores as the upgrade to 1.11 pins a saved design: its architecture sets
 * {@code siblingSpeed} to 1, and its parts are left as they are, so every run gives what
 * it gave.</li>
 * <li>The design's sibling speed, which the frozen engine's documents and JSON Schema
 * lack, is left out of the Java's: a document's architecture's, the JSON Schema's
 * property of the architecture, and the catalog's list of design-wide settings, which
 * holds it alone.</li>
 * <li>A sized part's summary of how its vCPUs counted is left out.</li>
 * <li>The description of a part's sibling speed, which says the default of schema 1.11,
 * reads as the frozen engine's.</li>
 * </ul>
 */
final class SiblingSpeedDepartures {

	/** The setting, on a design's architecture and on a part. */
	private static final String SIBLING_SPEED = "siblingSpeed";

	/** The architecture of a document, by its key in the document. */
	private static final String ARCHITECTURE = "architecture";

	/** The architecture's record in the JSON Schema, by its key among the definitions. */
	private static final String ARCHITECTURE_SCHEMA = "Architecture";

	/** How a sized part's vCPUs counted, in its sizing summary. */
	private static final String VCPUS = "vcpus";

	/** The sibling speed of vCPUs that are whole cores, as the upgrade writes it. */
	private static final int WHOLE_CORES = 1;

	/** The description of a part's sibling speed, as the frozen engine wrote it. */
	private static final String RECORDED_DESCRIPTION = "With 2 or more vCPU: the vCPUs are hyperthreads, two per "
			+ "physical core (as on most cloud instances), and a vCPU whose core's other vCPU is busy too runs at "
			+ "this speed, so a busy pair does twice this much work (about 0.6 on Intel and AMD cores: 1.2 cores' "
			+ "work). Empty: every vCPU is a full core (as before; right for Graviton and other cores without "
			+ "hyperthreads). Ignored when the design sets a host machine (architecture.host), whose sibling speed "
			+ "applies instead.";

	private SiblingSpeedDepartures() {
	}

	/**
	 * Pins a document of the frozen engine's schema to whole cores, as the upgrade to
	 * 1.11 does: its architecture's sibling speed set to 1 unless it sets one.
	 * @param document the document, changed in place
	 */
	static void pin(ObjectNode document) {
		if (document.get(ARCHITECTURE) instanceof ObjectNode architecture && !architecture.has(SIBLING_SPEED)) {
			architecture.put(SIBLING_SPEED, WHOLE_CORES);
		}
	}

	/**
	 * Leaves the design's sibling speed out of an object of the Java's output, where it
	 * is a document's architecture or the JSON Schema's record of one.
	 * @param object a copy of an object of the Java's output, changed in place
	 * @param key the object's key in its parent; {@code null} for an item of an array or
	 * the output itself
	 */
	static void leaveOut(ObjectNode object, @Nullable String key) {
		if (ARCHITECTURE.equals(key)) {
			object.remove(SIBLING_SPEED);
		}
		if (ARCHITECTURE_SCHEMA.equals(key) && object.get("properties") instanceof ObjectNode properties) {
			properties.remove(SIBLING_SPEED);
		}
		if (object.has("workloadFields")) {
			// the catalog's list of the design-wide settings, the sibling speed alone
			object.remove("architectureFields");
		}
		if (object.get(VCPUS) instanceof ObjectNode vcpus && vcpus.has("basis")) {
			// a sized part's summary of how its vCPUs counted
			object.remove(VCPUS);
		}
	}

	/**
	 * A text of the Java's output as the frozen engine wrote it: a part's sibling speed's
	 * description as it was.
	 * @param java the Java's text
	 * @return the text, its description put back
	 */
	static String asRecordedText(String java) {
		return Resources.SIBLING_SPEED.equals(java) ? RECORDED_DESCRIPTION : java;
	}

}
