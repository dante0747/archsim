package io.archsim.engine.seeds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.confidence.ConfidenceLevel;
import io.archsim.engine.slo.RunFigures;
import io.archsim.engine.slo.SloResult;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Objectives;

/**
 * The verdict split and ranges of a group of runs that differ only in their seed. One run
 * answers for one random sequence of arrivals and service times; a few seeds show how
 * much of a verdict is the design and how much is luck, as in
 * {@code Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46}.
 * Runs that disagree on the verdict are borderline, and their confidence is capped at
 * medium.
 *
 * @param runs how many runs the group has
 * @param finished how many of them finished
 * @param seeds the group's lowest and highest seed
 * @param objectives how many objectives the design sets; none until a run finished
 * @param met how many finished runs meet every objective
 * @param missed how many miss one
 * @param undecided how many cannot be decided, for want of a figure
 * @param verdict what the runs say; {@code null} without objectives or a finished run, or
 * when a run cannot be decided and the others agree
 * @param borderline whether some runs meet the objectives and some miss them
 * @param confidenceCap the highest confidence a borderline verdict may carry;
 * {@code null} when the verdict is not borderline
 * @param latencyMs the latency percentiles' ranges across the finished runs
 * @param successRate the success rate's range, if a run has one
 * @param throughputRps the throughput's range, if a run has one
 * @param perRun each finished run's verdict and figures, in the group's order
 * @param text the summary in words
 */
public record SeedSummary(int runs, int finished, SeedSpan seeds, int objectives, int met, int missed, int undecided,
		@Nullable SeedVerdict verdict, boolean borderline, @Nullable ConfidenceLevel confidenceCap,
		LatencyRanges latencyMs, @Nullable ValueRange successRate, @Nullable ValueRange throughputRps,
		List<SeedRunResult> perRun, String text) {

	/** The highest confidence a borderline verdict may carry. */
	private static final ConfidenceLevel BORDERLINE_CAP = ConfidenceLevel.MEDIUM;

	/** Between the parts of the summary's text. */
	private static final String SEPARATOR = " · ";

	/**
	 * Copies the runs' results.
	 */
	public SeedSummary {
		perRun = List.copyOf(perRun);
	}

	/**
	 * Summarizes a group of runs against the design's objectives.
	 * @param runs the group's runs, in its order
	 * @param objectives the design's objectives
	 * @return the summary; a run without figures counts towards the runs, not the
	 * finished
	 */
	public static SeedSummary of(List<SeedRun> runs, Objectives objectives) {
		List<SeedRunResult> perRun = new ArrayList<>();
		int total = 0;
		for (SeedRun run : runs) {
			RunFigures figures = run.figures();
			if (figures != null) {
				SloResult result = SloResult.evaluate(objectives, figures);
				total = result.total();
				perRun.add(SeedRunResult.of(run.seed(), result.pass(), figures));
			}
		}
		return summary(runs.stream().map(SeedRun::seed).toList(), perRun, Tally.of(perRun, total));
	}

	private static SeedSummary summary(List<Long> seeds, List<SeedRunResult> perRun, Tally tally) {
		ValueRange p95 = range(perRun, SeedRunResult::p95);
		LatencyRanges latency = new LatencyRanges(range(perRun, SeedRunResult::p50), p95,
				range(perRun, SeedRunResult::p99));
		return new SeedSummary(seeds.size(), perRun.size(), span(seeds), tally.objectives(), tally.met(),
				tally.missed(), tally.undecided(), tally.verdict(), tally.borderline(),
				tally.borderline() ? BORDERLINE_CAP : null, latency, range(perRun, SeedRunResult::successRate),
				range(perRun, SeedRunResult::throughputRps), perRun, text(tally, p95, seeds));
	}

	private static SeedSpan span(List<Long> seeds) {
		if (seeds.isEmpty()) {
			return new SeedSpan(null, null);
		}
		return new SeedSpan(seeds.stream().min(Long::compare).orElseThrow(),
				seeds.stream().max(Long::compare).orElseThrow());
	}

	private static @Nullable ValueRange range(List<SeedRunResult> perRun,
			Function<SeedRunResult, @Nullable Double> figure) {
		return ValueRange.of(perRun.stream().<@Nullable Double>map(figure).toList()).orElse(null);
	}

	private static String text(Tally tally, @Nullable ValueRange p95, List<Long> seeds) {
		List<String> parts = new ArrayList<>();
		parts.add(tally.headline());
		if (p95 != null) {
			parts.add(p95.text("p95"));
		}
		parts.add(new Seeds(seeds).describe());
		return parts.stream().filter((part) -> !part.isEmpty()).collect(Collectors.joining(SEPARATOR));
	}

	/**
	 * The summary as the API writes it.
	 * @return its JSON
	 */
	public ObjectNode toJson() {
		return RecordCodec.write(this);
	}

	/**
	 * How the finished runs' verdicts split.
	 *
	 * @param finished how many runs finished
	 * @param objectives how many objectives the design sets
	 * @param met how many meet every objective
	 * @param missed how many miss one
	 * @param undecided how many cannot be decided
	 */
	private record Tally(int finished, int objectives, int met, int missed, int undecided) {

		static Tally of(List<SeedRunResult> perRun, int objectives) {
			int met = (int) perRun.stream().filter((run) -> Boolean.TRUE.equals(run.pass())).count();
			int missed = (int) perRun.stream().filter((run) -> Boolean.FALSE.equals(run.pass())).count();
			return new Tally(perRun.size(), objectives, met, missed, perRun.size() - met - missed);
		}

		boolean borderline() {
			return this.met > 0 && this.missed > 0;
		}

		@Nullable SeedVerdict verdict() {
			if (this.objectives == 0 || this.finished == 0) {
				return null;
			}
			if (borderline()) {
				return SeedVerdict.BORDERLINE;
			}
			if (this.met == this.finished) {
				return SeedVerdict.HOLDS;
			}
			return (this.missed == this.finished) ? SeedVerdict.FAILS : null;
		}

		/** The verdict split in words, or nothing without a finished run. */
		String headline() {
			if (this.finished == 0) {
				return "";
			}
			if (this.objectives == 0) {
				return this.finished + " runs, no objectives set";
			}
			if (this.met > 0 || this.missed == 0) {
				return "Meets the objectives in " + this.met + " of " + this.finished + " runs";
			}
			return "Misses the objectives in " + this.missed + " of " + this.finished + " runs";
		}

	}

}
