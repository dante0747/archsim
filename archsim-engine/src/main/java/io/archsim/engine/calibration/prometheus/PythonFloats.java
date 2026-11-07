package io.archsim.engine.calibration.prometheus;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.numeric.PythonNumbers;

/**
 * Python's {@code float(value)} of a JSON value that telemetry gave.
 */
final class PythonFloats {

	private PythonFloats() {
	}

	/**
	 * {@code float(value)}.
	 * @param value a number, a boolean or text
	 * @return its float
	 * @throws NumberFormatException where Python raises: text that is no float or a value
	 * of another kind (a {@code ValueError} or {@code TypeError}, which the series refuse
	 * as malformed), and a whole number beyond a float's range (an {@code OverflowError},
	 * which the Python series let through, so that its API answered 500)
	 */
	static double of(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue() ? 1 : 0;
		}
		if (value.isFloatingPointNumber()) {
			return value.doubleValue();
		}
		if (value.isIntegralNumber()) {
			return PythonNumbers.toFloat(value.bigIntegerValue());
		}
		if (value.isString()) {
			return PythonNumbers.parseFloat(value.stringValue());
		}
		throw new NumberFormatException("float() argument must be a string or a real number");
	}

}
