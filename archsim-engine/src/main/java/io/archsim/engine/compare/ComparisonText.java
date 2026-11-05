package io.archsim.engine.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.ReportFinding;
import io.archsim.engine.report.ReportProvenance;
import io.archsim.engine.text.PythonStrings;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * What changed between two runs, in words ({@code _explain}), and why they may differ
 * other than by their designs.
 */
final class ComparisonText {

	/** The input differences an explanation names; it counts the rest. */
	private static final int SHOWN_DIFFERENCES = 6;

	/** The part changes an explanation names, the largest first. */
	private static final int SHOWN_SHIFTS = 4;

	/** A change of utilization by this much (10 points) is worth naming. */
	private static final double UTILIZATION_SHIFT = 0.1;

	/** A data store's or cache's load changed by this share is worth naming. */
	private static final double LOAD_SHIFT = 0.1;

	/** A change of hit ratio by this much (5 points) is worth naming. */
	private static final double HIT_RATIO_SHIFT = 0.05;

	/** How Python writes a value that is not there. */
	private static final String NONE = "None";

	private static final String OTHER_SEEDS = "The runs use different random seeds, so small differences may be random "
			+ "variation.";

	private static final String OTHER_BUILDS = "Inputs are identical but results differ; this indicates different "
			+ "engine builds or platforms.";

	private ComparisonText() {
	}

	/**
	 * What changed, in words: the inputs, throughput, p95 latency, success rate, the
	 * bottleneck and the parts that changed most.
	 * @param facts what the comparison found
	 * @return the explanations, in that order
	 */
	static List<String> explanations(Facts facts) {
		List<String> explanations = new ArrayList<>();
		explanations.add(inputs(facts.labels(), facts.inputDiff()));
		throughput(facts.kpi(Kpi.THROUGHPUT_RPS)).ifPresent(explanations::add);
		latency(facts.kpi(Kpi.LATENCY_P95)).ifPresent(explanations::add);
		success(facts.kpi(Kpi.SUCCESS_RATE)).ifPresent(explanations::add);
		bottleneck(facts).ifPresent(explanations::add);
		shifts(facts.nodes()).ifPresent(explanations::add);
		return explanations;
	}

	/**
	 * Why the runs may differ other than by their designs: other seeds, other engines, or
	 * the same inputs and seed with other results.
	 * @param facts what the comparison found
	 * @return the warnings
	 */
	static List<String> warnings(Facts facts) {
		ReportProvenance first = facts.firstProvenance();
		ReportProvenance second = facts.secondProvenance();
		List<String> warnings = new ArrayList<>();
		boolean sameSeed = Objects.equals(first.seed(), second.seed());
		if (!sameSeed) {
			warnings.add(OTHER_SEEDS);
		}
		if (!Objects.equals(first.engineVersion(), second.engineVersion())) {
			warnings.add("The runs used different engine versions (" + Objects.toString(first.engineVersion(), NONE)
					+ " vs " + Objects.toString(second.engineVersion(), NONE) + ").");
		}
		if (facts.inputDiff().isEmpty() && !first.resultDigest().equals(second.resultDigest()) && sameSeed) {
			warnings.add(OTHER_BUILDS);
		}
		return warnings;
	}

	private static String inputs(Labels labels, List<InputDifference> differences) {
		if (differences.isEmpty()) {
			return labels.a() + " and " + labels.b() + " simulate identical inputs.";
		}
		String shown = differences.stream()
			.limit(SHOWN_DIFFERENCES)
			.map((difference) -> difference.label() + " " + PythonText.str(difference.a()) + " → "
					+ PythonText.str(difference.b()))
			.collect(Collectors.joining("; "));
		String more = (differences.size() > SHOWN_DIFFERENCES)
				? " (+" + (differences.size() - SHOWN_DIFFERENCES) + " more)" : "";
		return labels.b() + " differs from " + labels.a() + " in: " + shown + more + ".";
	}

	/**
	 * How successful throughput changed, when it changed either way from a figure above
	 * or below zero. A judged change has both figures and its delta.
	 * @param change how throughput changed
	 * @return the sentence; empty otherwise
	 */
	private static Optional<String> throughput(KpiChange change) {
		Double relative = change.relativeDelta();
		if (!change.verdict().judged() || relative == null) {
			return Optional.empty();
		}
		String way = (Objects.requireNonNull(change.delta()) > 0) ? "rose" : "fell";
		return Optional.of("Successful throughput " + way + " " + PythonFormat.format(Math.abs(relative), ".0%") + " ("
				+ requests(Objects.requireNonNull(change.a())) + " → " + requests(Objects.requireNonNull(change.b()))
				+ ").");
	}

	private static Optional<String> latency(KpiChange change) {
		if (!change.verdict().judged()) {
			return Optional.empty();
		}
		String way = (Objects.requireNonNull(change.delta()) < 0) ? "dropped" : "increased";
		return Optional.of("p95 latency " + way + " from " + milliseconds(Objects.requireNonNull(change.a())) + " to "
				+ milliseconds(Objects.requireNonNull(change.b())) + ".");
	}

	private static Optional<String> success(KpiChange change) {
		if (!change.verdict().judged()) {
			return Optional.empty();
		}
		return Optional.of("Success rate changed from " + share(Objects.requireNonNull(change.a())) + " to "
				+ share(Objects.requireNonNull(change.b())) + ".");
	}

	/**
	 * Where each run's first bottleneck is: moved, gone, come, or the same part.
	 * @param facts what the comparison found
	 * @return the sentence; empty when neither run has a bottleneck
	 */
	private static Optional<String> bottleneck(Facts facts) {
		Map<String, String> names = new LinkedHashMap<>();
		facts.nodes().forEach((node) -> names.put(node.id(), node.label()));
		ReportFinding first = facts.firstBottleneck();
		ReportFinding second = facts.secondBottleneck();
		if (first != null && second != null && !Objects.equals(first.nodeId(), second.nodeId())) {
			return Optional.of("The bottleneck moved from " + name(names, first) + " to " + name(names, second) + ".");
		}
		if (first != null && second == null) {
			return Optional.of(name(names, first) + " is no longer saturated in " + facts.labels().b() + ".");
		}
		if (first == null && second != null) {
			return Optional.of(name(names, second) + " becomes saturated in " + facts.labels().b() + ".");
		}
		if (first != null) {
			return Optional.of(name(names, first) + " is the bottleneck in both runs.");
		}
		return Optional.empty();
	}

	private static String name(Map<String, String> names, ReportFinding finding) {
		String id = finding.nodeId();
		return (id != null) ? names.getOrDefault(id, id) : NONE;
	}

	/**
	 * The parts whose utilization, load (a data store's or cache's) or hit ratio changed
	 * most: the largest first, equal ones by their words in reverse, as Python sorts
	 * {@code (size, words)} in reverse.
	 * @param nodes each part, side by side
	 * @return the sentence; empty when no part changed enough
	 */
	private static Optional<String> shifts(List<NodeComparison> nodes) {
		List<Shift> shifts = new ArrayList<>();
		for (NodeComparison node : nodes) {
			NodeFigures before = node.a();
			NodeFigures after = node.b();
			if (before != null && after != null) {
				utilizationShift(node.label(), before, after).ifPresent(shifts::add);
				loadShift(node, before, after).ifPresent(shifts::add);
				hitRatioShift(node.label(), before, after).ifPresent(shifts::add);
			}
		}
		if (shifts.isEmpty()) {
			return Optional.empty();
		}
		shifts.sort(
				Comparator.comparingDouble(Shift::size).thenComparing(Shift::words, PythonStrings::compare).reversed());
		return Optional.of("Largest component changes: "
				+ shifts.stream().limit(SHOWN_SHIFTS).map(Shift::words).collect(Collectors.joining("; ")) + ".");
	}

	private static Optional<Shift> utilizationShift(String label, NodeFigures before, NodeFigures after) {
		Double from = before.utilization();
		Double to = after.utilization();
		if (from == null || to == null || Math.abs(to - from) < UTILIZATION_SHIFT) {
			return Optional.empty();
		}
		return Optional
			.of(new Shift(Math.abs(to - from), label + " utilization " + percent(from) + " → " + percent(to)));
	}

	/**
	 * A data store's or cache's change of load, a share of the larger load; a load the
	 * run did not measure counts as none.
	 * @param node the part, side by side
	 * @param before its figures in the first run
	 * @param after its figures in the second run
	 * @return the change; empty when the part is neither or its load changed too little
	 */
	private static Optional<Shift> loadShift(NodeComparison node, NodeFigures before, NodeFigures after) {
		if (!DatabaseSpec.TYPE.equals(node.type()) && !CacheSpec.TYPE.equals(node.type())) {
			return Optional.empty();
		}
		double from = Objects.requireNonNullElse(before.arrivalRps(), 0.0);
		double to = Objects.requireNonNullElse(after.arrivalRps(), 0.0);
		double larger = Math.max(from, to);
		if (larger <= 0 || Math.abs(to - from) / larger < LOAD_SHIFT) {
			return Optional.empty();
		}
		return Optional.of(new Shift(Math.abs(to - from) / larger, node.label() + " load "
				+ PythonFormat.format(from, ",.0f") + " → " + PythonFormat.format(to, ",.0f") + " ops/s"));
	}

	private static Optional<Shift> hitRatioShift(String label, NodeFigures before, NodeFigures after) {
		Double from = before.hitRatio();
		Double to = after.hitRatio();
		if (from == null || to == null || Math.abs(to - from) < HIT_RATIO_SHIFT) {
			return Optional.empty();
		}
		return Optional.of(new Shift(Math.abs(to - from), label + " hit ratio " + percent(from) + " → " + percent(to)));
	}

	private static String percent(double share) {
		return PythonFormat.format(share, ".0%");
	}

	/**
	 * Requests per second, as {@code _fmt(v, "req/s")} writes them.
	 * @param rate the requests per second
	 * @return such as {@code 1,250 req/s}
	 */
	private static String requests(double rate) {
		return PythonFormat.format(rate, ",.0f") + " req/s";
	}

	/**
	 * Milliseconds, as {@code _fmt(v, "ms")} writes them.
	 * @param time the time
	 * @return such as {@code 95.0 ms}
	 */
	private static String milliseconds(double time) {
		return PythonFormat.format(time, ",.1f") + " ms";
	}

	/**
	 * A share, as {@code _fmt(v, "ratio")} writes it.
	 * @param share the share, from 0 to 1
	 * @return such as {@code 99.5%}
	 */
	private static String share(double share) {
		return PythonFormat.format(share, ".1%");
	}

	/**
	 * What the comparison found that the explanations and warnings put in words.
	 *
	 * @param labels what the comparison calls the runs
	 * @param inputDiff the settings the simulated designs differ in
	 * @param kpis how each run figure changed
	 * @param nodes each part of either run
	 * @param firstBottleneck the first run's first bottleneck finding; {@code null} for
	 * none
	 * @param secondBottleneck the second run's; {@code null} for none
	 * @param firstProvenance what the first run was
	 * @param secondProvenance what the second run was
	 */
	record Facts(Labels labels, List<InputDifference> inputDiff, Map<Kpi, KpiChange> kpis, List<NodeComparison> nodes,
			@Nullable ReportFinding firstBottleneck, @Nullable ReportFinding secondBottleneck,
			ReportProvenance firstProvenance, ReportProvenance secondProvenance) {

		/**
		 * Copies the lists and the changes, so the record does not change.
		 * @param labels what the comparison calls the runs
		 * @param inputDiff the settings the simulated designs differ in
		 * @param kpis how each run figure changed
		 * @param nodes each part of either run
		 * @param firstBottleneck the first run's first bottleneck finding
		 * @param secondBottleneck the second run's
		 * @param firstProvenance what the first run was
		 * @param secondProvenance what the second run was
		 */
		Facts {
			inputDiff = List.copyOf(inputDiff);
			kpis = Map.copyOf(kpis);
			nodes = List.copyOf(nodes);
		}

		KpiChange kpi(Kpi kpi) {
			return Objects.requireNonNull(this.kpis.get(kpi), kpi::key);
		}

	}

	/**
	 * A part's change worth naming.
	 *
	 * @param size how large it is: points of utilization or hit ratio, or a share of load
	 * @param words the change in words
	 */
	private record Shift(double size, String words) {
	}

}
