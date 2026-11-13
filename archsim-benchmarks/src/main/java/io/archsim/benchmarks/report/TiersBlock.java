package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.model.text.PythonText;

/**
 * The validation page's table of the proposed tier criteria, one row per experiment, and
 * which technologies meet them.
 */
final class TiersBlock {

	private static final String YES = "yes";

	private static final String NO = "no";

	private TiersBlock() {
	}

	/**
	 * The criteria of each experiment's latest run.
	 * @param comparisons the latest runs' comparisons, in the page's order
	 * @param history every kept run, by experiment
	 * @return the table and the technologies' sentence
	 */
	static String of(List<JsonNode> comparisons, Map<String, List<JsonNode>> history) {
		List<String> lines = new ArrayList<>(List.of("| Experiment | 1. Median knee error within ±25% ("
				+ Tiers.MIN_RUNS + "+ runs) | 2. p95 within a factor of two or 3 ms at or below the measured knee "
				+ "(2 of 3 runs) | 3a. No unstable step at or below the measured knee (2 of 3 runs) | 3b. Reproduced "
				+ "on a second machine, same protocol | All three |", "| --- | --- | --- | --- | --- | --- |"));
		Map<String, Boolean> met = new LinkedHashMap<>();
		for (JsonNode comparison : comparisons) {
			String experiment = comparison.get("experiment").stringValue();
			JsonNode criteria = Tiers.criteria(comparison, history.getOrDefault(experiment, List.of()));
			met.put(experiment, criteria.get("all").booleanValue());
			lines.add("| " + experiment + " | " + knee(criteria) + " | " + p95(criteria) + " | " + stable(criteria)
					+ " | " + reproduced(criteria.get("reproduced")) + " | " + yesNo(criteria.get("all")) + " |");
		}
		lines.add("");
		lines.add(technologies(Tiers.technologyStatus(met)));
		return String.join("\n", lines);
	}

	private static String knee(JsonNode criteria) {
		JsonNode knee = criteria.get("knee");
		int runs = knee.get("runs").intValue();
		int disturbed = knee.get("disturbedRuns").intValue();
		String leftOut = (disturbed > 0) ? "; " + disturbed + " disturbed " + plural("run", disturbed) + " not counted"
				: "";
		if (runs < Tiers.MIN_RUNS) {
			return "no: " + few(runs) + " (this run " + Figures.percent(knee.get("thisRun")) + ")" + leftOut;
		}
		return yesNo(knee.get("pass")) + " (median " + Figures.percent(knee.get("error")) + " over " + runs + " runs"
				+ leftOut + ")";
	}

	private static String p95(JsonNode criteria) {
		JsonNode p95 = criteria.get("p95");
		int steps = p95.get("steps").intValue();
		List<JsonNode> misses = p95.get("misses").valueStream().toList();
		String within;
		if (steps == 0) {
			within = "no: no step at or below the measured knee";
		}
		else if (!misses.isEmpty()) {
			within = Figures.rates(misses) + " miss (" + (steps - misses.size()) + " of " + steps + " steps within)";
		}
		else {
			within = steps + " of " + steps + " steps within";
		}
		return yesNo(p95.get("pass")) + ": " + within + runsPassing(criteria, p95);
	}

	private static String stable(JsonNode criteria) {
		JsonNode stable = criteria.get("stable");
		ArrayNode unstable = (ArrayNode) stable.get("unstable");
		String text;
		if (!unstable.isEmpty()) {
			text = Figures.rates(unstable.valueStream().toList()) + " unstable";
		}
		else {
			text = (criteria.at("/p95/steps").intValue() > 0) ? "none unstable"
					: "no step at or below the measured knee";
		}
		return yesNo(stable.get("pass")) + ": " + text + runsPassing(criteria, stable);
	}

	// "; 2 of 3 runs pass", or how many more runs are needed
	private static String runsPassing(JsonNode criteria, JsonNode criterion) {
		int runs = criteria.at("/knee/runs").intValue();
		return (runs < Tiers.MIN_RUNS) ? "; " + few(runs)
				: "; " + criterion.get("runsPassing").intValue() + " of " + runs + " runs pass";
	}

	private static String reproduced(JsonNode reproduced) {
		int runs = reproduced.get("runs").intValue();
		int machines = reproduced.get("machines").intValue();
		return yesNo(reproduced.get("pass")) + ": " + runs + " " + plural("run", runs) + " under "
				+ PythonText.str(reproduced.get("protocol")) + ", on " + machines + " " + plural("machine", machines);
	}

	// which technologies meet the thresholds: every
	// experiment that exercises one meets all three
	private static String technologies(ArrayNode technologies) {
		List<String> each = new ArrayList<>();
		List<String> validated = new ArrayList<>();
		technologies.forEach((technology) -> {
			String name = technology.get("name").stringValue();
			List<String> experiments = technology.get("experiments").valueStream().map(JsonNode::stringValue).toList();
			each.add(name + " (" + String.join(", ", experiments) + ")");
			if (technology.get("validated").booleanValue()) {
				validated.add(name);
			}
		});
		String verdict = validated.isEmpty() ? "None meets them on this data."
				: "Meeting them on this data: " + String.join(", ", validated) + ".";
		return "A technology meets the thresholds when every experiment that exercises it meets all three: "
				+ String.join("; ", each) + ". " + verdict;
	}

	private static String few(int runs) {
		return runs + " " + plural("run", runs) + ", " + Tiers.MIN_RUNS + " needed";
	}

	private static String plural(String word, int count) {
		return (count != 1) ? word + "s" : word;
	}

	private static String yesNo(JsonNode pass) {
		return pass.booleanValue() ? YES : NO;
	}

}
