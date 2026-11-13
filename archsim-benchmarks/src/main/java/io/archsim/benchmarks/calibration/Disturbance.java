package io.archsim.benchmarks.calibration;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * A disturbed calibration pass: its p95 more than ten times its median, a stall the low
 * load cannot explain. It is recorded, discarded and run once more.
 */
final class Disturbance {

	/** How many times its median a disturbed pass's p95 is, at least. */
	static final double TAIL = 10.0;

	private Disturbance() {
	}

	/**
	 * Whether a pass was disturbed.
	 * @param entry the pass
	 * @return whether its p95 is more than ten times its median
	 */
	static boolean isDisturbed(JsonNode entry) {
		JsonNode k6 = entry.get("k6");
		JsonNode p50 = PythonValues.get(k6, "p50");
		return PythonText.truthy(p50) && PythonValues.numberOrZero(k6, "p95") > TAIL * p50.doubleValue();
	}

}
