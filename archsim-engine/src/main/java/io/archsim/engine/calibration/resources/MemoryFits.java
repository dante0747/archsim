package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.resources.FitErrors.Comparison;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * A part's working set per replica, as the Python engine's {@code fit_memory} fits it: by
 * non-negative least squares against the request rate, requests held, or both, plus a
 * constant, memory at idle. The fit explains the whole working set, so a term it was not
 * given is zero (memory per request held: its floor, {@link #MIN_PER_REQUEST_MB}).
 */
public final class MemoryFits {

	/**
	 * The smallest memory per request held calibration sets (the setting must be above
	 * zero): about a socket's buffers.
	 */
	public static final double MIN_PER_REQUEST_MB = 0.01;

	private static final String BASELINE = "baseline";

	private static final String RATE = "rate";

	private static final String HELD = "held";

	/** A term whose values span less than this factor cannot be told from idle memory. */
	private static final double MIN_RANGE = 1.2;

	private static final int BASELINE_DIGITS = 3;

	private static final int PER_UNIT_DIGITS = 6;

	private static final List<Term> TERMS = List.of(new Term(RATE, "request rate"), new Term(HELD, "requests held"));

	private MemoryFits() {
	}

	/**
	 * Memory at idle plus MiB per request per second and per request held.
	 * @param workingSetMb each point's working set per replica, in MiB
	 * @param rate each point's request rate per replica; {@code null} to fit without it
	 * @param held each point's requests held per replica; {@code null} to fit without
	 * them
	 * @return the fit; empty without a point whose values are finite and the rate and
	 * requests held at least 0
	 * @throws IllegalArgumentException in the Python engine's words when neither the rate
	 * nor the requests held are given
	 */
	public static Optional<MemoryFit> fit(List<Double> workingSetMb, @Nullable List<Double> rate,
			@Nullable List<Double> held) {
		if (rate == null && held == null) {
			throw new IllegalArgumentException("fit the working set against the request rate, requests held, or both");
		}
		List<Integer> rows = rows(workingSetMb, rate, held);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		List<Double> workingSets = pick(workingSetMb, rows);
		Map<String, List<Double>> columns = new LinkedHashMap<>();
		columns.put(BASELINE, Collections.nCopies(rows.size(), 1.0));
		if (rate != null) {
			columns.put(RATE, pick(rate, rows));
		}
		if (held != null) {
			columns.put(HELD, pick(held, rows));
		}
		Map<String, Double> coefficients = LeastSquares.nonNegative(columns, workingSets)
			.orElseThrow(
					() -> new IllegalStateException("no non-negative fit, though the constant alone always solves"))
			.coefficients();
		return Optional.of(memoryFit(columns, coefficients, workingSets));
	}

	private static MemoryFit memoryFit(Map<String, List<Double>> columns, Map<String, Double> coefficients,
			List<Double> workingSets) {
		double baseline = PythonRound.round(coefficients.getOrDefault(BASELINE, 0.0), BASELINE_DIGITS);
		double perRps = PythonRound.round(coefficients.getOrDefault(RATE, 0.0), PER_UNIT_DIGITS);
		double held = coefficients.getOrDefault(HELD, 0.0);
		double perRequest = PythonRound.round(Math.max(MIN_PER_REQUEST_MB, held), PER_UNIT_DIGITS);
		List<Double> rates = columns.getOrDefault(RATE, Collections.nCopies(workingSets.size(), 0.0));
		List<Double> helds = columns.getOrDefault(HELD, Collections.nCopies(workingSets.size(), 0.0));
		List<Comparison> comparisons = new ArrayList<>();
		for (int i : FitErrors.busyHalf(columns.containsKey(RATE) ? rates : helds)) {
			comparisons.add(new Comparison(baseline + perRps * rates.get(i) + held * helds.get(i), workingSets.get(i)));
		}
		List<String> against = columns.keySet().stream().filter((name) -> !BASELINE.equals(name)).toList();
		return new MemoryFit(baseline, perRps, perRequest, FitErrors.maxRelative(comparisons), workingSets.size(),
				against, notes(columns, coefficients));
	}

	/**
	 * What the fit could not tell about each term: one that hardly changed, or one memory
	 * did not grow with.
	 * @param columns the columns fitted, by name
	 * @param coefficients each column's coefficient
	 * @return the notes, the rate's first
	 */
	private static List<String> notes(Map<String, List<Double>> columns, Map<String, Double> coefficients) {
		List<String> notes = new ArrayList<>();
		for (Term term : TERMS) {
			List<Double> values = columns.get(term.name());
			if (values == null) {
				continue;
			}
			double low = Collections.min(values);
			double high = Collections.max(values);
			if (high <= 0 || high < MIN_RANGE * low) {
				notes.add("the " + term.words() + " hardly changed over the range (" + PythonFormat.format(low, ",.3g")
						+ " to " + PythonFormat.format(high, ",.3g")
						+ " per replica), so memory that follows it cannot be told apart from memory at idle");
			}
			else if (coefficients.getOrDefault(term.name(), 0.0) == 0.0) {
				notes.add("memory did not grow with the " + term.words() + " over the range");
			}
		}
		return notes;
	}

	/**
	 * The points whose working set is finite and whose rate and requests held, where
	 * given, are finite and at least 0.
	 * @param workingSetMb each point's working set
	 * @param rate each point's rate; {@code null} when not given
	 * @param held each point's requests held; {@code null} when not given
	 * @return the points' indexes, in order
	 */
	private static List<Integer> rows(List<Double> workingSetMb, @Nullable List<Double> rate,
			@Nullable List<Double> held) {
		List<Integer> rows = new ArrayList<>();
		for (int i = 0; i < workingSetMb.size(); i++) {
			if (Double.isFinite(workingSetMb.get(i)) && usable(rate, i) && usable(held, i)) {
				rows.add(i);
			}
		}
		return rows;
	}

	private static boolean usable(@Nullable List<Double> values, int index) {
		if (values == null) {
			return true;
		}
		double value = values.get(index);
		return Double.isFinite(value) && value >= 0;
	}

	private static List<Double> pick(List<Double> values, List<Integer> rows) {
		return rows.stream().map(values::get).toList();
	}

	/**
	 * A term the working set is fitted against.
	 *
	 * @param name its column's name
	 * @param words what the notes call it
	 */
	private record Term(String name, String words) {

	}

}
