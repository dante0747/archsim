package io.archsim.cli;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.Change;
import io.archsim.engine.calibration.ReplaySummary;
import io.archsim.model.text.PythonFormat;

/**
 * What {@code archsim calibrate} prints of a calibration, as the Python CLI's
 * {@code _write_calibration} printed it: on standard error, each input it set with the
 * fit's error, its notes, each replayed metric against what was observed and the replay's
 * summary; then the calibrated design, on standard output or to a file.
 */
final class CalibrationText {

	/** An observed or simulated figure: thousands separated, two decimals. */
	private static final String FIGURE = ",.2f";

	private static final String NOT_AVAILABLE = "n/a";

	private CalibrationText() {
	}

	/**
	 * What the calibration did, in the Python CLI's lines.
	 * @param outcome the calibration
	 * @return the lines, in order
	 */
	private static List<String> lines(CalibrationOutcome outcome) {
		List<String> lines = new ArrayList<>();
		outcome.changes().forEach((change) -> lines.add("  set " + change.label() + fit(change)));
		outcome.notes().forEach((note) -> lines.add("  note: " + note));
		JsonNode calibration = outcome.document().path("calibration");
		calibration.path("replay").forEach((replayed) -> lines.add(replayed(replayed)));
		ReplaySummary.of(calibration.isMissingNode() ? null : calibration).ifPresent(lines::add);
		return lines;
	}

	/**
	 * Prints what the calibration did on standard error, and writes the calibrated design
	 * to a file, saying so, or prints it.
	 * @param outcome the calibration
	 * @param out the file to write the design to; {@code null} for standard output
	 * @param stdout standard output
	 * @param stderr standard error
	 * @throws CommandException when the file cannot be written
	 */
	static void write(CalibrationOutcome outcome, @Nullable String out, PrintWriter stdout, PrintWriter stderr) {
		lines(outcome).forEach(stderr::println);
		if (out == null) {
			JsonOutput.print(stdout, outcome.document());
			return;
		}
		CommandFiles.write(out, JsonOutput.file(outcome.document()));
		stderr.println("wrote " + out);
	}

	private static String fit(Change change) {
		Double error = change.fitError();
		return (error != null) ? " (fit error " + PythonFormat.format(error, ".0%") + ")" : "";
	}

	// "replay End to end p95: observed 87.36, simulated 82.22 (-6%)"
	private static String replayed(JsonNode replayed) {
		JsonNode simulated = replayed.path("simulated");
		JsonNode error = replayed.path("error");
		return "  replay %s %s: observed %s, simulated %s (%s)".formatted(replayed.get("label").stringValue(),
				replayed.get("metric").stringValue(),
				PythonFormat.format(replayed.get("observed").doubleValue(), FIGURE),
				simulated.isNumber() ? PythonFormat.format(simulated.doubleValue(), FIGURE) : NOT_AVAILABLE,
				error.isNumber() ? PythonFormat.format(error.doubleValue(), "+.0%") : NOT_AVAILABLE);
	}

}
