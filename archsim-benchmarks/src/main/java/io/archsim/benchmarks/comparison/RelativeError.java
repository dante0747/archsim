package io.archsim.benchmarks.comparison;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * How far a prediction is from a measurement: {@code (predicted - measured) / measured}.
 */
public final class RelativeError {

	private RelativeError() {
	}

	/**
	 * The relative error of a prediction.
	 * @param predicted the predicted number, or None
	 * @param measured the measured number, or None
	 * @return the error; None when either is None or the measured one is zero
	 */
	public static JsonNode of(JsonNode predicted, JsonNode measured) {
		if (PythonValues.isNone(predicted) || PythonValues.isNone(measured) || measured.doubleValue() == 0) {
			return PythonValues.none();
		}
		return PythonValues.divide(PythonValues.subtract(predicted, measured), measured);
	}

}
