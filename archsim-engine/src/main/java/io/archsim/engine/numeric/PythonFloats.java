package io.archsim.engine.numeric;

import tools.jackson.databind.JsonNode;

/**
 * Python's {@code float(value)} of a JSON value: a number, a boolean or text.
 */
public final class PythonFloats {

	private PythonFloats() {
	}

	/**
	 * {@code float(value)}.
	 * @param value a number, a boolean or text
	 * @return its float
	 * @throws NumberFormatException where Python raises: for text that is no float (a
	 * {@code ValueError}), a value of another kind (a {@code TypeError}) and a whole
	 * number beyond a float's range (an {@code OverflowError}); each caller words them as
	 * its Python counterpart handled them
	 */
	public static double of(JsonNode value) {
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
