package io.archsim.engine.provenance;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.model.catalog.Setting;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.text.PythonText;

/**
 * How the value of a part's setting reads in the list of inputs: a distribution as its
 * description, a load curve as Python writes its list of points, anything else as
 * {@link ValueText} writes it.
 */
final class FieldText {

	/** The kind of a setting that holds a distribution. */
	private static final String DISTRIBUTION = "distribution";

	/** The kind of a service time that changes with load. */
	private static final String LOAD_CURVE = "loadCurve";

	private FieldText() {
	}

	/**
	 * The text of a setting's value: the parsed config's, or what a technology fills in.
	 * @param setting the setting
	 * @param value its value, as JSON
	 * @return how it reads
	 */
	static String of(Setting setting, JsonNode value) {
		if (DISTRIBUTION.equals(setting.kind()) && value.isObject()) {
			return RecordCodec.read(value, Distribution.class).describe();
		}
		if (LOAD_CURVE.equals(setting.kind()) && value.isArray()) {
			return loadCurve(value);
		}
		return ValueText.describe(value, setting.unit());
	}

	/**
	 * A load curve as Python's {@code str()} writes a list of {@code LoadPoint} records:
	 * {@code [LoadPoint(rps=100.0, factor=1.2)]}.
	 * @param points the curve's points, each with {@code rps} and {@code factor}
	 * @return the text
	 */
	private static String loadCurve(JsonNode points) {
		List<String> texts = new ArrayList<>();
		for (JsonNode point : points) {
			texts.add("LoadPoint(rps=" + PythonText.repr(point.path("rps").doubleValue()) + ", factor="
					+ PythonText.repr(point.path("factor").doubleValue()) + ")");
		}
		return '[' + String.join(", ", texts) + ']';
	}

}
