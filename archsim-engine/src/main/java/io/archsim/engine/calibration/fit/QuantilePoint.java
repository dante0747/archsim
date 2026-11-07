package io.archsim.engine.calibration.fit;

import java.util.ArrayList;
import java.util.List;

/**
 * One point of an empirical quantile table: the share of observations at or below a
 * value.
 *
 * @param share the share, from 0 to 1
 * @param value the value, in milliseconds
 */
record QuantilePoint(double share, double value) {

	/**
	 * A table whose shares rise and whose values never fall, from 0 to 1: a point whose
	 * share does not rise is dropped, a value below the one before is raised to it.
	 * @param points the points, by rising share
	 * @return the table
	 */
	static List<QuantilePoint> monotone(List<QuantilePoint> points) {
		List<QuantilePoint> out = new ArrayList<>();
		for (QuantilePoint point : points) {
			if (!out.isEmpty() && point.share() <= out.getLast().share()) {
				continue;
			}
			out.add(out.isEmpty() ? point
					: new QuantilePoint(point.share(), Math.max(point.value(), out.getLast().value())));
		}
		if (out.getFirst().share() != 0.0) {
			out.addFirst(new QuantilePoint(0.0, 0.0));
		}
		if (out.getLast().share() != 1.0) {
			out.add(new QuantilePoint(1.0, out.getLast().value()));
		}
		return out;
	}

	/**
	 * A quantile of a table, interpolated linearly between its points.
	 * @param table the table, from share 0 to 1
	 * @param quantile the quantile
	 * @return its value; the table's last value past its end
	 */
	static double interpolate(List<QuantilePoint> table, double quantile) {
		for (int i = 1; i < table.size(); i++) {
			QuantilePoint low = table.get(i - 1);
			QuantilePoint high = table.get(i);
			if (low.share() <= quantile && quantile <= high.share()) {
				return (high.share() > low.share())
						? low.value()
								+ (high.value() - low.value()) * (quantile - low.share()) / (high.share() - low.share())
						: high.value();
			}
		}
		return table.getLast().value();
	}

}
