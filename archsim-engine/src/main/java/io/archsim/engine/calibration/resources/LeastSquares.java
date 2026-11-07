package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import io.archsim.engine.numeric.FloatSum;

/**
 * Least squares as the Python engine's resource fits solve them: the normal equations by
 * Gaussian elimination with partial pivoting, and non-negative least squares by trying
 * every subset of a handful of columns.
 */
final class LeastSquares {

	/** A pivot this small, relative to the largest entry, makes the system singular. */
	private static final double SINGULAR = 1e-10;

	/** A coefficient below this is negative. */
	private static final double NEGATIVE = -1e-12;

	/** How much smaller a later subset's squared error must be to replace the best. */
	private static final double RELATIVE_GAIN = 1e-9;

	private static final double ABSOLUTE_GAIN = 1e-12;

	private LeastSquares() {
	}

	/**
	 * The best fit whose coefficients are all at least 0, a smaller subset preferred on a
	 * tie.
	 * @param columns each column by name, in order
	 * @param values the values fitted
	 * @return each column's coefficient (0 for one left out), and the sum of squared
	 * errors; empty when no subset solves
	 */
	static Optional<Solution> nonNegative(Map<String, List<Double>> columns, List<Double> values) {
		List<String> names = List.copyOf(columns.keySet());
		Solution best = null;
		for (int size = 1; size <= names.size(); size++) {
			for (List<String> subset : combinations(names, size)) {
				Optional<Solution> solved = solve(columns, subset, values);
				if (solved.isPresent() && (best == null
						|| solved.get().squaredError() < best.squaredError() * (1 - RELATIVE_GAIN) - ABSOLUTE_GAIN)) {
					best = solved.get();
				}
			}
		}
		if (best == null) {
			return Optional.empty();
		}
		Map<String, Double> coefficients = new LinkedHashMap<>();
		for (String name : names) {
			coefficients.put(name, best.coefficients().getOrDefault(name, 0.0));
		}
		return Optional.of(new Solution(coefficients, best.squaredError()));
	}

	/**
	 * A linear system solved by Gaussian elimination with partial pivoting.
	 * @param matrix the coefficients, square
	 * @param rhs the right-hand side
	 * @return the solution; empty when the system is (near) singular
	 */
	static Optional<double[]> gaussian(double[][] matrix, double[] rhs) {
		int size = rhs.length;
		double[][] augmented = new double[size][];
		double scale = 0;
		for (int i = 0; i < size; i++) {
			augmented[i] = Arrays.copyOf(matrix[i], size + 1);
			augmented[i][size] = rhs[i];
			for (double entry : matrix[i]) {
				scale = Math.max(scale, Math.abs(entry));
			}
		}
		scale = (scale == 0) ? 1.0 : scale;
		for (int column = 0; column < size; column++) {
			int pivot = pivot(augmented, column);
			if (Math.abs(augmented[pivot][column]) <= SINGULAR * scale) {
				return Optional.empty();
			}
			double[] swapped = augmented[column];
			augmented[column] = augmented[pivot];
			augmented[pivot] = swapped;
			eliminate(augmented, column);
		}
		double[] solution = new double[size];
		for (int i = 0; i < size; i++) {
			solution[i] = augmented[i][size] / augmented[i][i];
		}
		return Optional.of(solution);
	}

	/**
	 * Ordinary least squares over some of the columns, kept only when no coefficient is
	 * negative.
	 * @param columns each column by name
	 * @param subset the names of the columns fitted
	 * @param values the values fitted
	 * @return the coefficients, each at least 0, and the sum of squared errors; empty
	 * when the system is singular or a coefficient negative
	 */
	private static Optional<Solution> solve(Map<String, List<Double>> columns, List<String> subset,
			List<Double> values) {
		List<List<Double>> chosen = subset.stream().map(columns::get).toList();
		Optional<double[]> solved = gaussian(gram(chosen), rhs(chosen, values));
		if (solved.isEmpty() || Arrays.stream(solved.get()).anyMatch((coefficient) -> coefficient < NEGATIVE)) {
			return Optional.empty();
		}
		double[] kept = Arrays.stream(solved.get()).map((coefficient) -> Math.max(0.0, coefficient)).toArray();
		Map<String, Double> fitted = new LinkedHashMap<>();
		for (int i = 0; i < subset.size(); i++) {
			fitted.put(subset.get(i), kept[i]);
		}
		double squaredError = FloatSum.of(IntStream.range(0, values.size()).mapToDouble((row) -> {
			double estimate = FloatSum
				.of(IntStream.range(0, kept.length).mapToDouble((index) -> kept[index] * chosen.get(index).get(row)));
			double residual = values.get(row) - estimate;
			return residual * residual;
		}));
		return Optional.of(new Solution(fitted, squaredError));
	}

	private static double[][] gram(List<List<Double>> columns) {
		int size = columns.size();
		double[][] gram = new double[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				gram[i][j] = dot(columns.get(i), columns.get(j));
			}
		}
		return gram;
	}

	private static double[] rhs(List<List<Double>> columns, List<Double> values) {
		return columns.stream().mapToDouble((column) -> dot(column, values)).toArray();
	}

	private static double dot(List<Double> first, List<Double> second) {
		return FloatSum
			.of(IntStream.range(0, first.size()).mapToDouble((index) -> first.get(index) * second.get(index)));
	}

	/**
	 * The row whose entry in a column is largest, from that column's row down.
	 * @param augmented the augmented matrix
	 * @param column the column
	 * @return the row, the first of equal ones
	 */
	private static int pivot(double[][] augmented, int column) {
		int pivot = column;
		for (int row = column + 1; row < augmented.length; row++) {
			if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
				pivot = row;
			}
		}
		return pivot;
	}

	private static void eliminate(double[][] augmented, int column) {
		for (int row = 0; row < augmented.length; row++) {
			if (row != column) {
				double factor = augmented[row][column] / augmented[column][column];
				for (int entry = column; entry < augmented[row].length; entry++) {
					augmented[row][entry] -= factor * augmented[column][entry];
				}
			}
		}
	}

	/**
	 * The subsets of a size, in the order Python's {@code itertools.combinations} gives
	 * them.
	 * @param names the names
	 * @param size how many each subset has
	 * @return the subsets
	 */
	private static List<List<String>> combinations(List<String> names, int size) {
		List<List<String>> all = new ArrayList<>();
		if (size == 0) {
			all.add(List.of());
			return all;
		}
		for (int first = 0; first <= names.size() - size; first++) {
			for (List<String> rest : combinations(names.subList(first + 1, names.size()), size - 1)) {
				List<String> subset = new ArrayList<>();
				subset.add(names.get(first));
				subset.addAll(rest);
				all.add(subset);
			}
		}
		return all;
	}

	/**
	 * A fit's coefficients and its sum of squared errors.
	 *
	 * @param coefficients each column's coefficient, by name
	 * @param squaredError the sum of squared errors
	 */
	record Solution(Map<String, Double> coefficients, double squaredError) {

		Solution {
			coefficients = Collections.unmodifiableMap(new LinkedHashMap<>(coefficients));
		}

	}

}
