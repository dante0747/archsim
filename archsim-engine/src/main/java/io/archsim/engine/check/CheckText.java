package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.slo.Better;
import io.archsim.engine.slo.Objective;
import io.archsim.engine.slo.SloCheck;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * A check in words: its headline ({@code headline}), and the pull-request comment
 * {@code archsim check --format markdown} writes ({@code to_markdown}).
 */
public final class CheckText {

	/**
	 * The first line of a check's comment, by which a later run finds and replaces it.
	 */
	public static final String MARKER = "<!-- archsim-check -->";

	/** A latency of at least this many milliseconds is written without decimals. */
	private static final double WHOLE_MS = 100;

	/** A rate of at least this many requests per second is written in thousands. */
	private static final double THOUSANDS = 1000;

	/** A cost change smaller than a dollar a month is not worth naming. */
	private static final double DOLLAR = 1;

	/**
	 * A success rate dropping by more than this (0.05 points) is worth naming in the
	 * headline, whether it regressed or not.
	 */
	private static final double SUCCESS_DROP_WORTH_NAMING = 0.0005;

	/** A share written as a percentage. */
	private static final int PERCENT = 100;

	/** The characters of a result's digest a comment shows. */
	private static final int DIGEST_SHOWN = 12;

	private CheckText() {
	}

	/**
	 * A check in one sentence, such as {@code p95 goes from 180 ms to 410 ms (objective
	 * 300 ms) — fails; breaking point drops from 2.1k req/s to 1.3k req/s; cost +$140 a
	 * month}.
	 * @param current this change's measurement
	 * @param baseline the baseline's; {@code null} without a baseline
	 * @return the headline
	 */
	public static String headline(Measurement current, @Nullable Measurement baseline) {
		List<String> parts = new ArrayList<>();
		parts.add(latency(current, baseline));
		for (SloCheck check : current.slo().checks()) {
			if (check.key() != Objective.P95 && Boolean.FALSE.equals(check.pass())) {
				parts.add(check.label().toLowerCase(Locale.ROOT) + " misses its objective");
			}
		}
		if (baseline != null) {
			knee(current, baseline).ifPresent(parts::add);
			cost(current, baseline).ifPresent(parts::add);
			success(current, baseline).ifPresent(parts::add);
		}
		return String.join("; ", parts);
	}

	/**
	 * A check as a pull-request comment: the verdict, the headline, the comparison with
	 * the baseline and the objectives, the bottleneck, the confidence, the notes and what
	 * the check fails on.
	 * @param result the check
	 * @param reportUrl where the full report is; {@code null} for nowhere
	 * @return the comment, in Markdown, ending in a newline
	 */
	public static String markdown(CheckResult result, @Nullable String reportUrl) {
		Measurement current = result.current();
		List<String> lines = new ArrayList<>(heading(result));
		if (result.baseline() != null) {
			lines.addAll(regressionTable(result.regressions()));
		}
		lines.addAll(objectivesTable(current.slo().checks()));
		String bottleneck = current.bottleneck();
		if (bottleneck != null && !bottleneck.isEmpty()) {
			lines.add("Bottleneck: " + MarkdownText.escaped(bottleneck) + ".");
		}
		confidence(current.confidence()).ifPresent(lines::add);
		result.notes().forEach((note) -> lines.add(MarkdownText.escaped(note)));
		lines.add(footer(result, reportUrl));
		return String.join("\n", lines) + "\n";
	}

	private static String latency(Measurement current, @Nullable Measurement baseline) {
		Double before = (baseline != null) ? baseline.p95() : null;
		String text;
		if (before != null && !ms(before).equals(ms(current.p95()))) {
			text = "p95 goes from " + ms(before) + " to " + ms(current.p95());
		}
		else if (before != null) {
			text = "p95 stays at " + ms(current.p95());
		}
		else {
			text = "p95 " + ms(current.p95());
		}
		Optional<SloCheck> objective = current.slo()
			.checks()
			.stream()
			.filter((check) -> check.key() == Objective.P95)
			.findFirst();
		if (objective.isPresent()) {
			text += " (objective " + ms(objective.get().target()) + ")"
					+ (Boolean.FALSE.equals(objective.get().pass()) ? " — fails" : "");
		}
		return text;
	}

	/**
	 * How the breaking point moved, when it did: between two loads, or below the lightest
	 * load swept.
	 * @param current this change's measurement
	 * @param baseline the baseline's
	 * @return the part of the headline; empty when it did not move or was not swept
	 */
	private static Optional<String> knee(Measurement current, Measurement baseline) {
		Double now = current.knee();
		Double before = baseline.knee();
		if (now != null && before != null && now.doubleValue() != before.doubleValue()) {
			String way = (now < before) ? "drops" : "rises";
			return Optional.of("breaking point " + way + " from " + rps(before) + " to " + rps(now));
		}
		List<Double> range = current.kneeRange();
		List<Double> baseRange = baseline.kneeRange();
		if (range == null || range.isEmpty() || baseRange == null || baseRange.isEmpty()) {
			return Optional.empty();
		}
		return belowTheLightestLoad(now, before, range.getFirst());
	}

	/**
	 * A breaking point that moved from or to below the lightest load swept: a design that
	 * misses its objectives at every load has no breaking point.
	 * @param now this change's breaking point; {@code null} below the lightest load
	 * @param before the baseline's; {@code null} below the lightest load
	 * @param lightest the lightest load swept
	 * @return the part of the headline; empty unless exactly one is below
	 */
	private static Optional<String> belowTheLightestLoad(@Nullable Double now, @Nullable Double before,
			double lightest) {
		String below = "below " + rps(lightest);
		if (before != null && now == null) {
			return Optional.of("breaking point drops from " + rps(before) + " to " + below);
		}
		if (before == null && now != null) {
			return Optional.of("breaking point rises from " + below + " to " + rps(now));
		}
		return Optional.empty();
	}

	private static Optional<String> cost(Measurement current, Measurement baseline) {
		Double now = current.costMonthly();
		Double before = baseline.costMonthly();
		if (now == null || before == null || Math.abs(now - before) < DOLLAR) {
			return Optional.empty();
		}
		double delta = now - before;
		return Optional.of("cost " + ((delta > 0) ? "+" : "-") + usd(delta) + " a month");
	}

	private static Optional<String> success(Measurement current, Measurement baseline) {
		Double now = current.successRate();
		Double before = baseline.successRate();
		if (now == null || before == null || before - now <= SUCCESS_DROP_WORTH_NAMING) {
			return Optional.empty();
		}
		return Optional.of("success rate " + percent(before) + " → " + percent(now));
	}

	private static List<String> heading(CheckResult result) {
		Measurement current = result.current();
		String status = result.pass() ? "passes" : "fails";
		String icon = result.pass() ? "✅" : "❌";
		String scenario = current.scenarioId();
		String what = MarkdownText.escaped(current.name())
				+ ((scenario != null && !scenario.isEmpty()) ? " · scenario " + MarkdownText.code(scenario) : "");
		return List.of(MARKER, "### " + icon + " ArchSim check " + status + ": " + what, "", result.headline() + ".",
				"");
	}

	/**
	 * The comparison with the baseline, each figure a row, and how a breaking point was
	 * measured when one was.
	 * @param regressions how each figure changed
	 * @return the table's lines
	 */
	private static List<String> regressionTable(List<Regression> regressions) {
		List<String> lines = new ArrayList<>(
				List.of("| Metric | Base | This change | Change | |", "| --- | --- | --- | --- | --- |"));
		Double kneeStep = null;
		for (Regression row : regressions) {
			if (row.metric() == Metric.KNEE) {
				kneeStep = row.resolution();
			}
			lines.add("| " + row.label() + " | " + cell(row, row.baseline()) + " | " + cell(row, row.current()) + " | "
					+ change(row) + " | " + (row.regressed() ? "regressed" : "") + " |");
		}
		lines.add("");
		if (kneeStep != null && kneeStep != 0) {
			lines.add("Breaking point: the highest load within the objectives. Both designs are swept on one load grid "
					+ "in steps of " + rps(kneeStep) + ", so a drop of one step is within its resolution and not a "
					+ "regression.");
			lines.add("");
		}
		return lines;
	}

	private static List<String> objectivesTable(List<SloCheck> checks) {
		if (checks.isEmpty()) {
			return List.of("No objectives are set on this design, so only regressions can fail the check.", "");
		}
		List<String> lines = new ArrayList<>(List.of("| Objective | Target | Result | |", "| --- | --- | --- | --- |"));
		for (SloCheck check : checks) {
			String sign = (check.better() == Better.LOWER) ? "≤" : "≥";
			lines.add("| " + check.label() + " | " + sign + " " + figure(check.key(), check.target()) + " | "
					+ figure(check.key(), check.actual()) + " | " + mark(check.pass()) + " |");
		}
		lines.add("");
		return lines;
	}

	/**
	 * Whether an objective was met, in a word.
	 * @param pass whether the run met it; {@code null} when it has no figure for it
	 * @return {@code met}, {@code missed} or {@code n/a}
	 */
	private static String mark(@Nullable Boolean pass) {
		if (pass == null) {
			return "n/a";
		}
		return pass ? "met" : "missed";
	}

	private static Optional<String> confidence(MeasuredConfidence confidence) {
		String level = confidence.level();
		if (level == null || level.isEmpty()) {
			return Optional.empty();
		}
		return Optional
			.of("**" + PythonText.capitalize(level) + " confidence**: " + MarkdownText.escaped(confidence.reason())
					+ ". To raise it: " + MarkdownText.escaped(confidence.howToRaise()));
	}

	private static String footer(CheckResult result, @Nullable String reportUrl) {
		Measurement current = result.current();
		String digest = current.resultDigest();
		int codePoints = digest.codePointCount(0, digest.length());
		String shown = digest.substring(0, digest.offsetByCodePoints(0, Math.min(DIGEST_SHOWN, codePoints)));
		String failsOn = result.failOn().stream().map(FailOn::json).collect(Collectors.joining(", "));
		return "Fails on: " + failsOn + ". These are ArchSim's estimates under the design's assumptions (engine "
				+ Objects.toString(current.engineVersion(), MarkdownText.NONE) + ", result `" + shown
				+ "`), not measurements."
				+ ((reportUrl != null && !reportUrl.isEmpty()) ? " [Full report](" + reportUrl + ")" : "");
	}

	/**
	 * A row's figure ({@code _cell}): a breaking point missed at every load swept is
	 * below the lightest.
	 * @param row the row
	 * @param value its figure, the baseline's or this change's
	 * @return the cell
	 */
	private static String cell(Regression row, @Nullable Double value) {
		if (row.metric() == Metric.KNEE && value == null) {
			return "< " + rps(row.lightest());
		}
		if (value == null) {
			return "n/a";
		}
		return switch (row.metric()) {
			case P95 -> ms(value);
			case SUCCESS_RATE -> percent(value);
			case COST_MONTHLY -> "$" + PythonFormat.format(value, ",.0f");
			case KNEE -> rps(value);
		};
	}

	private static String change(Regression row) {
		Double change = row.change();
		if (change == null) {
			return "n/a";
		}
		return (row.metric() == Metric.SUCCESS_RATE) ? PythonFormat.format(change * PERCENT, "+.1f") + " pp"
				: PythonFormat.format(change * PERCENT, "+.0f") + "%";
	}

	private static String figure(Objective objective, @Nullable Double value) {
		return switch (objective) {
			case P95, P99, MESSAGE_AGE -> ms(value);
			case AVAILABILITY -> (value != null) ? percent(value) : "n/a";
			case THROUGHPUT -> rps(value);
		};
	}

	/**
	 * A latency, as {@code _ms} writes it.
	 * @param value the latency, in milliseconds; {@code null} for none
	 * @return such as {@code 410 ms} or {@code 95.0 ms}
	 */
	private static String ms(@Nullable Double value) {
		if (value == null) {
			return "n/a";
		}
		return PythonFormat.format(value, (value >= WHOLE_MS) ? ",.0f" : ",.1f") + " ms";
	}

	/**
	 * A rate, as {@code _rps} writes it.
	 * @param value the requests per second; {@code null} for none
	 * @return such as {@code 2.1k req/s} or {@code 800 req/s}
	 */
	private static String rps(@Nullable Double value) {
		if (value == null) {
			return "n/a";
		}
		return (value >= THOUSANDS) ? PythonFormat.format(value / THOUSANDS, ".1f") + "k req/s"
				: PythonFormat.format(value, ",.0f") + " req/s";
	}

	private static String usd(double value) {
		return "$" + PythonFormat.format(Math.abs(value), ",.0f");
	}

	private static String percent(double share) {
		return PythonFormat.format(share * PERCENT, ".2f") + "%";
	}

}
