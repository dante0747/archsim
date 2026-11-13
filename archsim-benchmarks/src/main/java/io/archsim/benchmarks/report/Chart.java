package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.report.ChartPanel.Series;
import io.archsim.benchmarks.values.PythonValues;

/**
 * An experiment's chart ({@code docs/validation/<experiment>.svg}): p95 latency on a
 * logarithmic axis with the objective, and the successful throughput against the offered
 * load, measured and predicted. Plain SVG, readable on light and dark backgrounds.
 */
final class Chart {

	/** ArchSim's prediction: orange. */
	private static final String PREDICTED = "#e8710a";

	/** Measured: blue. */
	private static final String MEASURED = "#1a73e8";

	private static final int THROUGHPUT_LEFT = 340;

	private Chart() {
	}

	/**
	 * The chart of a comparison.
	 * @param comparison the experiment's {@code comparison.json}
	 * @return the SVG, ending with a newline
	 */
	static String of(JsonNode comparison) {
		List<JsonNode> rows = comparison.get("rows").valueStream().toList();
		List<JsonNode> loads = rows.stream().map((row) -> row.get("offeredRps")).toList();
		ChartPanel latency = new ChartPanel(0, "p95 latency (log scale)", loads,
				List.of(new Series(MEASURED, values(rows, "measured", "p95"), false),
						new Series(PREDICTED, values(rows, "predicted", "p95"), true)),
				true, "ms", comparison.get("objective").get("p95Ms").doubleValue());
		ChartPanel throughput = new ChartPanel(THROUGHPUT_LEFT, "throughput (successful)", loads,
				List.of(new Series(ChartPanel.INK, loads, true),
						new Series(MEASURED, values(rows, "measured", "throughputRps"), false),
						new Series(PREDICTED, values(rows, "predicted", "throughputRps"), true)),
				false, "req/s", null);
		List<String> svg = new ArrayList<>();
		svg.add("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 680 214\" width=\"680\" height=\"214\" "
				+ "font-family=\"-apple-system, Segoe UI, Helvetica, Arial, sans-serif\">");
		svg.addAll(latency.lines());
		svg.addAll(throughput.lines());
		svg.add("<rect x=\"440\" y=\"5\" width=\"14\" height=\"3\" fill=\"" + MEASURED
				+ "\"/><text x=\"458\" y=\"10\" font-size=\"10\" fill=\"" + ChartPanel.INK + "\">measured</text>");
		svg.add("<rect x=\"520\" y=\"5\" width=\"14\" height=\"3\" fill=\"" + PREDICTED
				+ "\"/><text x=\"538\" y=\"10\" font-size=\"10\" fill=\"" + ChartPanel.INK
				+ "\">ArchSim (predicted first)</text>");
		svg.add("</svg>");
		return String.join("\n", svg) + "\n";
	}

	private static List<JsonNode> values(List<JsonNode> rows, String side, String key) {
		return rows.stream().map((row) -> PythonValues.get(row.get(side), key)).toList();
	}

}
