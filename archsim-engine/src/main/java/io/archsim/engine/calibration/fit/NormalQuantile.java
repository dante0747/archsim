package io.archsim.engine.calibration.fit;

/**
 * The standard normal distribution's inverse: the value below which a share of it lies,
 * as Python's {@code statistics.NormalDist().inv_cdf} computes it (Wichura's algorithm
 * AS241, 1988: rational approximations accurate to about 1e-16).
 */
final class NormalQuantile {

	private static final double HALF = 0.5;

	private static final double CENTRAL = 0.425;

	private static final double CENTRAL_R = 0.180625;

	private static final double TAIL_SPLIT = 5.0;

	private static final double NEAR_TAIL_SHIFT = 1.6;

	private static final double[] CENTRAL_NUMERATOR = { 2509.0809287301227, 33430.57558358813, 67265.7709270087,
			45921.95393154987, 13731.69376550946, 1971.5909503065513, 133.14166789178438, 3.3871328727963665 };

	private static final double[] CENTRAL_DENOMINATOR = { 5226.495278852854, 28729.085735721943, 39307.89580009271,
			21213.794301586597, 5394.196021424751, 687.1870074920579, 42.31333070160091, 1.0 };

	private static final double[] NEAR_NUMERATOR = { 0.0007745450142783414, 0.022723844989269184, 0.2417807251774506,
			1.2704582524523684, 3.6478483247632045, 5.769497221460691, 4.630337846156546, 1.4234371107496835 };

	private static final double[] NEAR_DENOMINATOR = { 1.0507500716444169e-09, 0.0005475938084995345,
			0.015198666563616457, 0.14810397642748008, 0.6897673349851, 1.6763848301838038, 2.053191626637759, 1.0 };

	private static final double[] FAR_NUMERATOR = { 2.0103343992922881e-07, 2.7115555687434876e-05,
			0.0012426609473880784, 0.026532189526576124, 0.29656057182850487, 1.7848265399172913, 5.463784911164114,
			6.657904643501103 };

	private static final double[] FAR_DENOMINATOR = { 2.0442631033899397e-15, 1.421511758316446e-07,
			1.8463183175100548e-05, 0.0007868691311456133, 0.014875361290850615, 0.1369298809227358, 0.599832206555888,
			1.0 };

	private NormalQuantile() {
	}

	/**
	 * The standard normal value below which a share of the distribution lies.
	 * @param share the share, strictly between 0 and 1
	 * @return the value; NaN for NaN, which Python's check lets through
	 * @throws IllegalArgumentException when the share is 0 or less, or 1 or more, in
	 * Python's words
	 */
	static double of(double share) {
		if (share <= 0.0 || share >= 1.0) {
			throw new IllegalArgumentException("p must be in the range 0.0 < p < 1.0");
		}
		double offset = share - HALF;
		if (Math.abs(offset) <= CENTRAL) {
			double central = CENTRAL_R - offset * offset;
			return horner(CENTRAL_NUMERATOR, central) * offset / horner(CENTRAL_DENOMINATOR, central);
		}
		double tail = Math.sqrt(-StrictMath.log((offset <= 0.0) ? share : 1.0 - share));
		double value;
		if (tail <= TAIL_SPLIT) {
			tail -= NEAR_TAIL_SHIFT;
			value = horner(NEAR_NUMERATOR, tail) / horner(NEAR_DENOMINATOR, tail);
		}
		else {
			tail -= TAIL_SPLIT;
			value = horner(FAR_NUMERATOR, tail) / horner(FAR_DENOMINATOR, tail);
		}
		return (offset < 0.0) ? -value : value;
	}

	/**
	 * A polynomial, highest power first, evaluated as the Python nests it.
	 * @param coefficients the coefficients, highest power first
	 * @param variable the variable
	 * @return the polynomial's value
	 */
	private static double horner(double[] coefficients, double variable) {
		double value = coefficients[0];
		for (int i = 1; i < coefficients.length; i++) {
			value = value * variable + coefficients[i];
		}
		return value;
	}

}
