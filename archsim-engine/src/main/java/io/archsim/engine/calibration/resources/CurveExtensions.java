package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import io.archsim.engine.numeric.Median;
import io.archsim.model.text.PythonFormat;

/**
 * How CPU per request continues above the top of a calibration staircase, as the Python
 * engine's {@code extend_load_curve} carries it. The fall over the rates that kept the
 * CPUs at least {@link #BASE_BUSY} busy (the base) is fitted against {@code ln(rate)}
 * over every pass at them, and continued from the top rate's median until the CPUs fill,
 * but never further above the top, in {@code ln(rate)}, than the base spans below it. It
 * stays flat above the top unless the top kept the CPUs at least {@link #MIN_BUSY} busy,
 * the base has two rates, and the fall exceeds {@link #STANDARD_ERRORS} standard errors
 * of its fit.
 */
public final class CurveExtensions {

	/** How busy the top rate must have kept the CPUs for the curve to be carried on. */
	public static final double MIN_BUSY = 0.45;

	/**
	 * How busy a rate must have kept the CPUs to be part of the fall's fit: below it the
	 * cost also falls with the host's power management, which does not continue.
	 */
	public static final double BASE_BUSY = 0.30;

	/**
	 * How many standard errors of its fit the fall must exceed (the Python engine's
	 * {@code t}).
	 */
	public static final double STANDARD_ERRORS = 2.0;

	private static final double MS_PER_S = 1000.0;

	/** How many halvings find where the CPUs fill. */
	private static final int BISECTIONS = 60;

	private CurveExtensions() {
	}

	/**
	 * The extension above the top rate.
	 * @param passes each pass's rate per replica and CPU per request in milliseconds;
	 * points that are not finite, without a rate or without CPU are left out
	 * @param cores the replica's CPUs
	 * @param bounds how busy the top and the base must be, and how far the fall must
	 * stand out
	 * @return the extension; empty without passes or without CPUs
	 */
	public static Optional<CurveExtension> extend(List<RatePoint> passes, double cores, Bounds bounds) {
		List<RatePoint> points = passes.stream()
			.filter((pass) -> Double.isFinite(pass.rate()) && Double.isFinite(pass.value()) && pass.rate() > 0
					&& pass.value() > 0)
			.sorted(Comparator.comparingDouble(RatePoint::rate).thenComparingDouble(RatePoint::value))
			.toList();
		if (points.isEmpty() || cores <= 0) {
			return Optional.empty();
		}
		Passes byRate = Passes.of(points, MS_PER_S * cores);
		double top = points.getLast().rate();
		Top start = new Top(top, byRate.median(top), byRate.busy(top));
		if (start.busy() < bounds.minBusy()) {
			return Optional.of(start.flat("the top rate kept the CPUs " + PythonFormat.format(start.busy(), ".0%")
					+ " busy, under " + PythonFormat.format(bounds.minBusy(), ".0%")));
		}
		if (start.busy() >= 1.0) {
			return Optional.of(start.flat("the top rate filled the CPUs"));
		}
		return Optional.of(fall(byRate, start, bounds));
	}

	/**
	 * The extension of a top that kept the CPUs busy enough, by the fall over the base.
	 * @param passes the passes
	 * @param top the top rate
	 * @param bounds how busy the base must be, and how far the fall must stand out
	 * @return the extension, flat when the base has one rate or the fall does not stand
	 * out
	 */
	private static CurveExtension fall(Passes passes, Top top, Bounds bounds) {
		List<RatePoint> base = passes.points()
			.stream()
			.filter((point) -> passes.busy(point.rate()) >= bounds.baseBusy())
			.toList();
		TreeSet<Double> rates = new TreeSet<>(base.stream().map(RatePoint::rate).toList());
		if (rates.size() < 2) {
			return top
				.flat("one rate kept the CPUs at least " + PythonFormat.format(bounds.baseBusy(), ".0%") + " busy");
		}
		Slope slope = Slope.of(base.stream().map((point) -> Math.log(point.rate() / top.rate())).toList(),
				base.stream().map(RatePoint::value).toList());
		if (slope.value() >= 0 || slope.error() == null || -slope.value() <= bounds.standardErrors() * slope.error()) {
			String what = (slope.error() == null) ? "no scatter to tell it from"
					: PythonFormat.format(bounds.standardErrors(), "g") + " standard errors";
			return top.flat("no fall above the noise (" + what + ")", slope);
		}
		double stop = (top.ms() + slope.value()) / -slope.value();
		if (stop <= 0) {
			return top.flat("a fall so steep the CPUs' busy time would shrink", slope);
		}
		return top.carried(slope, Math.min(stop, Math.log(top.rate() / rates.first())), passes.fill());
	}

	/**
	 * How busy the top and the base must be, and how far the fall must stand out.
	 *
	 * @param minBusy how busy the top rate must have kept the CPUs
	 * @param baseBusy how busy a rate must have kept the CPUs to be part of the fit
	 * @param standardErrors how many standard errors of its fit the fall must exceed
	 */
	public record Bounds(double minBusy, double baseBusy, double standardErrors) {

		/** The Python engine's bounds. */
		public static final Bounds DEFAULT = new Bounds(MIN_BUSY, BASE_BUSY, STANDARD_ERRORS);

	}

	/**
	 * The passes, and their CPU per request by rate.
	 *
	 * @param points the passes, by rate
	 * @param byRate each rate's passes' CPU per request, by rising rate
	 * @param fill the CPUs' milliseconds per second
	 */
	private record Passes(List<RatePoint> points, Map<Double, List<Double>> byRate, double fill) {

		static Passes of(List<RatePoint> points, double fill) {
			Map<Double, List<Double>> byRate = new LinkedHashMap<>();
			points.forEach(
					(point) -> byRate.computeIfAbsent(point.rate(), (rate) -> new ArrayList<>()).add(point.value()));
			return new Passes(points, byRate, fill);
		}

		double median(double rate) {
			return Median.of(Objects.requireNonNull(this.byRate.get(rate)).stream().mapToDouble(Double::doubleValue));
		}

		/**
		 * The share of the CPUs a rate kept busy.
		 * @param rate one of the passes' rates
		 * @return its rate times its passes' median CPU per request, over the CPUs
		 */
		double busy(double rate) {
			return rate * median(rate) / this.fill;
		}

	}

	/**
	 * The top rate, its CPU per request and how busy it kept the CPUs.
	 *
	 * @param rate the rate
	 * @param ms its CPU per request, in milliseconds
	 * @param busy the share of the CPUs it kept busy
	 */
	private record Top(double rate, double ms, double busy) {

		CurveExtension flat(String reason) {
			return new CurveExtension(this.rate, this.ms, this.busy, 0.0, null, List.of(), reason);
		}

		CurveExtension flat(String reason, Slope slope) {
			return new CurveExtension(this.rate, this.ms, this.busy, slope.value(), slope.error(), List.of(), reason);
		}

		/**
		 * The fall carried from the top until the CPUs fill, or as far as it may go.
		 * @param slope the fall, with a standard error
		 * @param furthest how far above the top it may go, in {@code ln(rate)}
		 * @param fill the CPUs' milliseconds per second
		 * @return the extension
		 */
		CurveExtension carried(Slope slope, double furthest, double fill) {
			double low = 0.0;
			double high = furthest;
			boolean filled = busyMs(high, slope) >= fill;
			if (filled) {
				for (int i = 0; i < BISECTIONS; i++) {
					double middle = (low + high) / 2;
					if (busyMs(middle, slope) < fill) {
						low = middle;
					}
					else {
						high = middle;
					}
				}
			}
			double endRps = this.rate * Math.exp(high);
			double error = Objects.requireNonNull(slope.error());
			String reason = "a fall of " + PythonFormat.format(-slope.value(), ".3g") + " ms per unit of ln(rate) ("
					+ PythonFormat.format(-slope.value() / error, ".1f") + " standard errors) carried from "
					+ PythonFormat.format(this.rate, ",.0f") + " to " + PythonFormat.format(endRps, ",.0f") + " req/s ("
					+ (filled ? "where the CPUs fill" : "one measured stretch on") + ")";
			return new CurveExtension(this.rate, this.ms, this.busy, slope.value(), slope.error(),
					List.of(new RatePoint(endRps, this.ms + slope.value() * high)), reason);
		}

		private double busyMs(double logRatio, Slope slope) {
			return this.rate * Math.exp(logRatio) * (this.ms + slope.value() * logRatio);
		}

	}

}
