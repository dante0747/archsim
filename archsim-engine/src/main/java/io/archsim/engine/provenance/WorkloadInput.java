package io.archsim.engine.provenance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * The workload fields listed as inputs, in the order the analysis lists them, each with
 * how its value reads: illustrative when it is the default, assumed otherwise, unless the
 * document records it.
 */
enum WorkloadInput {

	/** The baseline load. */
	RPS("rps", "Baseline load") {
		@Override
		String text(Workload workload) {
			return PythonFormat.format(workload.rps(), "g") + " req/s";
		}
	},

	/** The share of reads. */
	READ_RATIO("readRatio", "Read share") {
		@Override
		String text(Workload workload) {
			return PythonFormat.format(workload.readRatio(), ".0%");
		}
	},

	/** The keys requests spread over. */
	KEY_COUNT("keyCount", "Distinct keys") {
		@Override
		String text(Workload workload) {
			return PythonFormat.format(workload.keyCount(), ",");
		}
	},

	/** How unevenly requests spread over the keys. */
	KEY_SKEW("keySkew", "Key skew (Zipf s)") {
		@Override
		String text(Workload workload) {
			return PythonFormat.format(workload.keySkew(), "g");
		}
	},

	/** How the load changes over the run, and how requests arrive. */
	PATTERN("pattern", "Traffic pattern") {
		@Override
		String text(Workload workload) {
			return RecordCodec.tag(workload.pattern()) + ", " + workload.arrivalProcess().json() + " arrivals";
		}
	};

	/** The workload when none of it is written, as JSON. */
	private static final ObjectNode DEFAULTS = RecordCodec
		.write(RecordCodec.read(JsonNodeFactory.instance.objectNode(), Workload.class));

	private final String key;

	private final String label;

	WorkloadInput(String key, String label) {
		this.key = key;
		this.label = label;
	}

	/**
	 * How the field's value reads, as the Python engine formats it.
	 * @param workload the design's workload
	 * @return the text, such as {@code 100 req/s} or {@code 90%}
	 */
	abstract String text(Workload workload);

	/**
	 * The field's input.
	 * @param document the design
	 * @param workload the design's workload, as JSON, to compare with its default and its
	 * record
	 * @return the input, with its value and provenance
	 */
	Input of(ArchitectureDocument document, ObjectNode workload) {
		JsonNode value = workload.path(this.key);
		Provenance provenance = Recorded.of(document.provenance().get(InputPaths.workload(this.key)), value)
			.orClassify(() -> (!workload.has(this.key) || Values.equal(value, DEFAULTS.path(this.key)))
					? Provenance.archSimDefault() : Provenance.assumed());
		return new Input(InputScope.WORKLOAD, null, "Workload", this.key, this.label, text(document.workload()),
				provenance);
	}

}
