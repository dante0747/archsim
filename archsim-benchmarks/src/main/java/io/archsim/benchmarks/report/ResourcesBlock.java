package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The validation page's table of CPU and memory: each container's predicted against its
 * measured busy cores and working set, at the highest step at or below both knees, and
 * the median error over every step up to it.
 */
final class ResourcesBlock {

	private static final int COLUMNS = 7;

	private static final int MEMORY_COLUMNS = 3;

	private ResourcesBlock() {
	}

	/**
	 * The table, from the latest runs' comparisons.
	 * @param comparisons the latest runs' comparisons, in the page's order
	 * @return the table and its notes
	 */
	static String of(List<JsonNode> comparisons) {
		List<JsonNode> withResources = comparisons.stream()
			.filter((comparison) -> PythonText.truthy(PythonValues.get(comparison, "resources")))
			.toList();
		if (withResources.isEmpty()) {
			return "_No CPU or memory recorded yet._";
		}
		Table table = new Table(withResources.stream().anyMatch(ResourcesBlock::memoryMeasured));
		withResources.forEach(table::add);
		List<String> lines = new ArrayList<>(table.lines());
		lines.addAll(notes(withResources));
		lines.addAll(table.notModelled());
		return String.join("\n", lines);
	}

	// a part's busy cores at the top step, measured and predicted, and its median error
	private static String row(JsonNode comparison, JsonNode top, Map.Entry<String, JsonNode> part, JsonNode cell) {
		String experiment = comparison.get("experiment").stringValue();
		JsonNode summary = part.getValue();
		JsonNode medianError = summary.get("medianAbsCoresError");
		String median = PythonValues.isNone(medianError) ? Figures.NONE
				: Figures.magnitude(medianError) + " (" + PythonText.str(summary.get("steps")) + ")";
		boolean notModelled = PageTexts.standard().whyNoCores(part.getKey()).isPresent();
		return "| [" + experiment + "](#" + experiment + ") | " + PageTexts.standard().part(part.getKey()) + " | "
				+ Figures.rate(top.get("offeredRps")) + " | " + Figures.cores(cell.at("/measured/cores")) + " | "
				+ (notModelled ? "not modelled" : Figures.cores(cell.at("/predicted/cores"))) + " | "
				+ Figures.percent(cell.at("/error/cores")) + " | " + median + " |";
	}

	// a prediction alone, with nothing measured, is not shown
	private static String memoryCells(JsonNode cell) {
		JsonNode measured = cell.at("/measured/memoryMiB");
		if (PythonValues.isNone(measured)) {
			return " - | - | - |";
		}
		return " " + Figures.memory(measured) + " | " + Figures.memory(cell.at("/predicted/memoryMiB")) + " | "
				+ Figures.percent(cell.at("/error/memory")) + " |";
	}

	private static List<String> notes(List<JsonNode> withResources) {
		Set<String> measuredBy = new TreeSet<>();
		Set<String> predictedBy = new TreeSet<>();
		withResources.forEach((comparison) -> {
			measuredBy.add(comparison.at("/resources/measuredBy").stringValue());
			predictedBy.add(predictedByText(comparison.at("/resources/predictedBy")));
		});
		List<String> methods = measuredBy.stream().map(PageTexts.standard()::measuredBy).toList();
		return List.of("", "Each part at the highest load step at or below both knees, the measured one and the "
				+ "model's: past the model's own knee its queue holds every request the real service would time out, "
				+ "so its CPU reads fully busy and its memory grows without bound, and the knee error already counts "
				+ "that. Busy cores are CPU seconds per second; errors are (predicted - measured) / measured.", "",
				"Measured: " + String.join("; ", methods) + ". Predicted: " + String.join("; ", predictedBy) + ".");
	}

	/**
	 * Where a run's predicted CPU and memory came from, in words.
	 * @param predictedBy the comparison's {@code resources.predictedBy}
	 * @return the predictions, a later simulation, or none
	 */
	static String predictedByText(JsonNode predictedBy) {
		if (predictedBy.isString() && predictedBy.stringValue().equals("predictions")) {
			return "ArchSim's predictions, saved before the measurements";
		}
		if (predictedBy.isObject()) {
			String at = PythonText.str(PythonValues.get(predictedBy, "at"));
			return "simulated again with engine " + PythonText.str(PythonValues.get(predictedBy, "engineVersion"))
					+ " on " + Figures.date(at) + " from each run's calibration "
					+ "alone, because the original predictions did not record CPU or memory (the latency "
					+ "predictions are the original ones)";
		}
		return "none";
	}

	/**
	 * Whether any step of a comparison measured a container's memory.
	 * @param comparison a comparison
	 * @return whether it did
	 */
	static boolean memoryMeasured(JsonNode comparison) {
		return comparison.get("rows")
			.valueStream()
			.flatMap((row) -> row.path("resources").valueStream())
			.anyMatch((cell) -> !PythonValues.isNone(cell.at("/measured/memoryMiB")));
	}

	/**
	 * The steps where CPU and memory are compared: at or below both knees.
	 * @param comparison a comparison
	 * @return the steps
	 */
	static List<JsonNode> resourceSteps(JsonNode comparison) {
		return Knees.atOrBelowBothKnees(comparison.get("rows").valueStream().toList(), comparison.get("measuredKnee"),
				comparison.get("predictedKnee"));
	}

	/**
	 * The highest step at or below both knees.
	 * @param comparison a comparison
	 * @return the step; empty when there is none
	 */
	static Optional<JsonNode> topBelowKnees(JsonNode comparison) {
		List<JsonNode> below = resourceSteps(comparison);
		return below.isEmpty() ? Optional.empty()
				: Optional.of(below.stream()
					.reduce((best, step) -> (PythonValues.compare(step.get("offeredRps"), best.get("offeredRps")) > 0)
							? step : best)
					.orElseThrow());
	}

	/**
	 * The table's rows, with the memory columns when any run measured memory, and the
	 * parts the model gives no CPU.
	 */
	private static final class Table {

		private final boolean memory;

		private final List<String> lines = new ArrayList<>();

		private final Map<String, String> unmodelled = new LinkedHashMap<>();

		Table(boolean memory) {
			this.memory = memory;
			this.lines
				.add("| Experiment | Part | Load step (req/s) | Busy cores measured | Busy cores predicted | Error | "
						+ "Median error up to both knees (steps) |"
						+ (memory ? " Memory measured | Memory predicted | Error |" : ""));
			this.lines.add("| --- ".repeat(COLUMNS + (memory ? MEMORY_COLUMNS : 0)) + "|");
		}

		// a row per part at the run's top step below both knees
		void add(JsonNode comparison) {
			Optional<JsonNode> top = topBelowKnees(comparison);
			if (top.isEmpty()) {
				return;
			}
			comparison.at("/resources/belowKnee").properties().forEach((part) -> {
				JsonNode cell = top.get().path("resources").path(part.getKey());
				if (cell.isObject()) {
					PageTexts.standard()
						.whyNoCores(part.getKey())
						.ifPresent((why) -> this.unmodelled.put(PageTexts.standard().part(part.getKey()), why));
					this.lines.add(row(comparison, top.get(), part, cell) + (this.memory ? memoryCells(cell) : ""));
				}
			});
		}

		List<String> lines() {
			return List.copyOf(this.lines);
		}

		// the parts the model gives no CPU, and why
		List<String> notModelled() {
			if (this.unmodelled.isEmpty()) {
				return List.of();
			}
			List<String> reasons = new ArrayList<>();
			this.unmodelled.forEach((name, why) -> reasons.add(name + " (" + why + ")"));
			return List.of("", "Not modelled: " + String.join("; ", reasons) + ".");
		}

	}

}
