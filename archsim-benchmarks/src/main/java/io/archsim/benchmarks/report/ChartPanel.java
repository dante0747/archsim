package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * One panel of an experiment's chart: an axis of offered load, a linear or logarithmic
 * axis of the series' values, its ticks and labels, an optional dashed objective line,
 * and each series as a line of dots. Every coordinate is written as {@code report.py}
 * wrote it, so the SVG is the same to the byte (ADR-0070).
 *
 * @param left where the panel starts on the chart
 * @param title the panel's title
 * @param loads the offered load of each point
 * @param series the series drawn
 * @param logarithmic whether the value axis is logarithmic
 * @param unit the value axis's label
 * @param objective the value of a dashed objective line, if any
 */
record ChartPanel(int left, String title, List<JsonNode> loads, List<Series> series, boolean logarithmic, String unit,
		@Nullable Double objective) {

	static final String INK = "#888888";

	private static final double WIDTH = 330.0;

	private static final double HEIGHT = 210.0;

	private static final double TOP = 30.0;

	private static final double MARGIN = 46.0;

	private static final double BOTTOM = 34.0;

	private static final double RIGHT_GAP = 10;

	private static final double PLOT_WIDTH = WIDTH - MARGIN - RIGHT_GAP;

	private static final double PLOT_HEIGHT = HEIGHT - TOP - BOTTOM;

	private static final double LOAD_HEADROOM = 1.05;

	/** How far a tick mark reaches left of the value axis. */
	private static final double TICK = 3;

	/** How far a tick's label ends left of the value axis. */
	private static final double TICK_LABEL_GAP = 6;

	/** How far a label's baseline lies below the line it labels. */
	private static final double BASELINE = 3;

	/** How far the loads' labels lie below the load axis. */
	private static final double LOAD_LABEL_GAP = 13;

	/** Where the value axis's title stands, from the panel's left. */
	private static final int UNIT_LABEL_LEFT = 11;

	/** How far a caption's baseline lies above what it captions. */
	private static final double CAPTION_GAP = 4;

	ChartPanel {
		loads = List.copyOf(loads);
		series = List.copyOf(series);
	}

	/**
	 * The panel's SVG elements, one per line; none when it has no positive value to draw.
	 * @return the elements
	 */
	List<String> lines() {
		List<Double> values = new ArrayList<>();
		this.series.forEach((drawn) -> drawn.values()
			.stream()
			.filter((value) -> !PythonValues.isNone(value) && value.doubleValue() > 0)
			.forEach((value) -> values.add(value.doubleValue())));
		if (this.objective != null) {
			values.add(this.objective);
		}
		if (values.isEmpty()) {
			return List.of();
		}
		ValueAxis axis = this.logarithmic ? ValueAxis.logarithmic(values) : ValueAxis.linear(values);
		double loadMax = this.loads.stream().mapToDouble(JsonNode::doubleValue).max().orElseThrow() * LOAD_HEADROOM;
		List<String> lines = new ArrayList<>(frame());
		ticks(axis, lines);
		loadLabels(loadMax, lines);
		labels(lines);
		if (this.objective != null) {
			objectiveLine(axis, this.objective, lines);
		}
		this.series.forEach((drawn) -> drawn(axis, loadMax, drawn, lines));
		return lines;
	}

	private List<String> frame() {
		String axis = repr(this.left + MARGIN);
		String bottom = repr(TOP + PLOT_HEIGHT);
		String right = repr(this.left + MARGIN + PLOT_WIDTH);
		return List.of(
				"<text x=\"" + axis + "\" y=\"16\" font-size=\"12\" font-weight=\"600\" fill=\"" + INK + "\">"
						+ this.title + "</text>",
				"<line x1=\"" + axis + "\" y1=\"" + bottom + "\" x2=\"" + right + "\" y2=\"" + bottom + "\" stroke=\""
						+ INK + "\" stroke-width=\"1\"/>",
				"<line x1=\"" + axis + "\" y1=\"" + repr(TOP) + "\" x2=\"" + axis + "\" y2=\"" + bottom + "\" stroke=\""
						+ INK + "\" stroke-width=\"1\"/>");
	}

	private void ticks(ValueAxis axis, List<String> lines) {
		for (double tick : axis.ticks()) {
			double height = y(axis, tick);
			lines.add("<line x1=\"" + repr(this.left + MARGIN - TICK) + "\" y1=\"" + oneDecimal(height) + "\" x2=\""
					+ repr(this.left + MARGIN + PLOT_WIDTH) + "\" y2=\"" + oneDecimal(height) + "\" stroke=\"" + INK
					+ "\" stroke-opacity=\"0.18\"/>");
			lines.add("<text x=\"" + repr(this.left + MARGIN - TICK_LABEL_GAP) + "\" y=\""
					+ oneDecimal(height + BASELINE) + "\" font-size=\"9\" text-anchor=\"end\" fill=\"" + INK + "\">"
					+ ValueAxis.label(tick) + "</text>");
		}
	}

	private void loadLabels(double loadMax, List<String> lines) {
		for (JsonNode load : this.loads) {
			lines.add("<text x=\"" + oneDecimal(x(load.doubleValue(), loadMax)) + "\" y=\""
					+ repr(TOP + PLOT_HEIGHT + LOAD_LABEL_GAP) + "\" font-size=\"9\" text-anchor=\"middle\" fill=\""
					+ INK + "\">" + Figures.format(load, ",.0f") + "</text>");
		}
	}

	private void labels(List<String> lines) {
		String middle = repr(TOP + PLOT_HEIGHT / 2);
		String side = Integer.toString(this.left + UNIT_LABEL_LEFT);
		lines.add("<text x=\"" + repr(this.left + MARGIN + PLOT_WIDTH / 2) + "\" y=\"" + repr(HEIGHT - CAPTION_GAP)
				+ "\" font-size=\"10\" text-anchor=\"middle\" fill=\"" + INK + "\">offered load (req/s)</text>");
		lines.add("<text x=\"" + side + "\" y=\"" + middle + "\" font-size=\"10\" text-anchor=\"middle\" fill=\"" + INK
				+ "\" transform=\"rotate(-90 " + side + " " + middle + ")\">" + this.unit + "</text>");
	}

	private void objectiveLine(ValueAxis axis, double value, List<String> lines) {
		double height = y(axis, value);
		lines.add("<line x1=\"" + repr(this.left + MARGIN) + "\" y1=\"" + oneDecimal(height) + "\" x2=\""
				+ repr(this.left + MARGIN + PLOT_WIDTH) + "\" y2=\"" + oneDecimal(height) + "\" stroke=\"" + INK
				+ "\" stroke-dasharray=\"2 3\"/>");
		lines.add("<text x=\"" + repr(this.left + MARGIN + PLOT_WIDTH - 2) + "\" y=\""
				+ oneDecimal(height - CAPTION_GAP) + "\" font-size=\"9\" text-anchor=\"end\" fill=\"" + INK
				+ "\">objective " + PythonFormat.format(value, "g") + " ms</text>");
	}

	private void drawn(ValueAxis axis, double loadMax, Series drawn, List<String> lines) {
		List<Point> points = new ArrayList<>();
		for (int i = 0; i < this.loads.size(); i++) {
			JsonNode value = drawn.values().get(i);
			if (!PythonValues.isNone(value) && value.doubleValue() > 0) {
				points.add(new Point(oneDecimal(x(this.loads.get(i).doubleValue(), loadMax)),
						oneDecimal(y(axis, value.doubleValue()))));
			}
		}
		if (points.size() > 1) {
			String dash = drawn.dashed() ? " stroke-dasharray=\"5 4\"" : "";
			lines.add("<polyline points=\"" + String.join(" ", points.stream().map(Point::text).toList())
					+ "\" fill=\"none\" stroke=\"" + drawn.color() + "\" stroke-width=\"2\"" + dash + "/>");
		}
		for (Point point : points) {
			lines.add("<circle cx=\"" + point.x() + "\" cy=\"" + point.y() + "\" r=\"2.6\" fill=\"" + drawn.color()
					+ "\"/>");
		}
	}

	private double x(double load, double loadMax) {
		return this.left + MARGIN + PLOT_WIDTH * load / loadMax;
	}

	private static double y(ValueAxis axis, double value) {
		return TOP + PLOT_HEIGHT * (1 - axis.fraction(value));
	}

	private static String repr(double value) {
		return PythonText.repr(value);
	}

	private static String oneDecimal(double value) {
		return PythonFormat.format(value, ".1f");
	}

	/**
	 * A series of a panel.
	 *
	 * @param color its color
	 * @param values its value at each load, None where it has none
	 * @param dashed whether its line is dashed
	 */
	record Series(String color, List<JsonNode> values, boolean dashed) {

		Series {
			values = List.copyOf(values);
		}

	}

	/**
	 * A point of a series, its coordinates as the SVG writes them.
	 *
	 * @param x its x
	 * @param y its y
	 */
	private record Point(String x, String y) {

		String text() {
			return this.x + "," + this.y;
		}

	}

}
