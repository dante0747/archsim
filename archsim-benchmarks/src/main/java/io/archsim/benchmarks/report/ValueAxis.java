package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.archsim.model.text.PythonFormat;

/**
 * A chart panel's value axis: from zero to a tenth above the largest value, with ticks at
 * 1, 2 or 5 times a power of ten; or logarithmic, between the powers of ten around the
 * values, with a tick at each.
 *
 * @param low the axis's lowest value
 * @param high the axis's highest value
 * @param logarithmic whether it is logarithmic
 */
record ValueAxis(double low, double high, boolean logarithmic) {

	private static final double TEN = 10;

	private static final double HEADROOM = 1.1;

	/** How far past the top a logarithmic tick may lie and still be drawn. */
	private static final double TOP_SLACK = 1.0001;

	/** Ticks wanted on a linear axis: a fourth of its height a power of ten. */
	private static final double QUARTERS = 4;

	private static final double FIVE_STEPS = 20;

	private static final double TWO_STEPS = 8;

	private static final int FIVE = 5;

	private static final double THOUSAND = 1000;

	/**
	 * A logarithmic axis between the powers of ten around the values.
	 * @param values positive values, at least one
	 * @return the axis
	 */
	static ValueAxis logarithmic(List<Double> values) {
		double low = StrictMath.pow(TEN, Math.floor(StrictMath.log10(Collections.min(values))));
		double high = StrictMath.pow(TEN, Math.ceil(StrictMath.log10(Collections.max(values))));
		return new ValueAxis(low, high, true);
	}

	/**
	 * A linear axis from zero to a tenth above the largest value.
	 * @param values values, at least one
	 * @return the axis
	 */
	static ValueAxis linear(List<Double> values) {
		return new ValueAxis(0.0, Collections.max(values) * HEADROOM, false);
	}

	/**
	 * Where a value lies on the axis, from 0 at its bottom to 1 at its top.
	 * @param value the value
	 * @return its fraction of the axis
	 */
	double fraction(double value) {
		if (this.logarithmic) {
			double bottom = StrictMath.log10(this.low);
			return (StrictMath.log10(Math.max(value, this.low)) - bottom) / (StrictMath.log10(this.high) - bottom);
		}
		return (value - this.low) / (this.high - this.low);
	}

	/**
	 * The axis's ticks, from its bottom up.
	 * @return the values ticked
	 */
	List<Double> ticks() {
		List<Double> ticks = new ArrayList<>();
		if (this.logarithmic) {
			for (double tick = this.low; tick <= this.high * TOP_SLACK; tick *= TEN) {
				ticks.add(tick);
			}
			return ticks;
		}
		double step = (this.high > 0) ? StrictMath.pow(TEN, Math.floor(StrictMath.log10(this.high / QUARTERS))) : 1;
		if (this.high / step > FIVE_STEPS) {
			step *= FIVE;
		}
		else if (this.high / step > TWO_STEPS) {
			step *= 2;
		}
		for (double tick = 0.0; tick <= this.high; tick += step) {
			ticks.add(tick);
		}
		return ticks;
	}

	/**
	 * A tick's label: {@code 0.5}, {@code 200} or {@code 2k}.
	 * @param tick the value ticked
	 * @return its label
	 */
	static String label(double tick) {
		return (tick < THOUSAND) ? PythonFormat.format(tick, "g") : PythonFormat.format(tick / THOUSAND, "g") + "k";
	}

}
