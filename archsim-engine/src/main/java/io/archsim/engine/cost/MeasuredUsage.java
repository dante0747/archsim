package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.BaseRate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.LatencyStats;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

/**
 * What each part handles at the workload's steady base load after a run: its rate
 * measured in the run's time buckets at the base load (the whole window when no bucket is
 * at the base), scaled to the base exactly, so fan-out, cache misses and retries are
 * kept; split into reads and writes as the static load estimate splits it.
 */
final class MeasuredUsage {

	/**
	 * A time bucket whose offered rate is within this factor of the base rate is at it.
	 */
	private static final double BASE_BUCKET_SLACK = 1.25;

	/** How far above the slack an offered rate may round and still be at the base. */
	private static final double ROUNDING = 1e-9;

	private static final double MILLIS_PER_SECOND = 1000.0;

	private MeasuredUsage() {
	}

	/**
	 * Each part's usage at the base load, measured in a run.
	 * @param document the design the run simulated
	 * @param loads the load estimator, for each part's share of reads
	 * @param report the run's report
	 * @return the run's base load: each part's usage by its id, for the parts with an
	 * arrival rate
	 * @throws IllegalArgumentException when the report lists no parts: a run's report
	 * lists every part of its design, and a design has a client
	 */
	static BaseLoad.Measured of(ArchitectureDocument document, LoadEstimator loads, RunReport report) {
		if (report.nodes().isEmpty()) {
			throw new IllegalArgumentException("a run's report lists the parts of the design it ran");
		}
		Workload workload = document.workload();
		double base = BaseRate.of(workload);
		Map<String, @Nullable NodeLoad> estimated = BaseUsage.atBase(document, loads);
		double workloadReads = Math.min(1.0, Math.max(0.0, workload.readRatio()));
		Double offered = report.summary().offeredRps();
		double scale = (offered != null && offered > 0) ? base / offered : 1.0;
		List<Integer> buckets = baseBuckets(report, base);
		List<Double> offeredAtBase = at(report.timeseries().offeredRps(), buckets);
		double offeredBase = offeredAtBase.isEmpty() ? 0.0 : mean(offeredAtBase);
		Map<String, Usage> usage = new HashMap<>();
		for (ReportNode node : report.nodes()) {
			Double rate = node.summary().arrivalRps();
			if (rate != null) {
				List<Double> seen = at(node.series("arrivalRps"), buckets);
				double rps = (!seen.isEmpty() && offeredBase > 0) ? mean(seen) * base / offeredBase : rate * scale;
				double share = readShare(estimated.get(node.id()), workloadReads);
				usage.put(node.id(), new Usage(rps, rps * share, rps * (1 - share), duration(node, buckets)));
			}
		}
		return new BaseLoad.Measured(usage, report, scale);
	}

	/**
	 * The run's time buckets inside its window whose offered rate is at the base load;
	 * all of those inside its window when none is.
	 * @param report the run's report
	 * @param base the base rate
	 * @return the buckets' indexes, in order
	 */
	private static List<Integer> baseBuckets(RunReport report, double base) {
		List<@Nullable Double> times = report.timeseries().t();
		List<@Nullable Double> offered = report.timeseries().offeredRps();
		double from = report.window().warmupS();
		double to = report.window().durationS();
		List<Integer> inside = new ArrayList<>();
		List<Integer> atBase = new ArrayList<>();
		for (int i = 0; i < Math.min(times.size(), offered.size()); i++) {
			// a bucket without its time counts as outside the window;
			// the simulator writes every bucket's time
			Double time = times.get(i);
			if (time != null && from <= time && time < to) {
				inside.add(i);
				if (Objects.requireNonNullElse(offered.get(i), 0.0) <= base * BASE_BUCKET_SLACK + ROUNDING) {
					atBase.add(i);
				}
			}
		}
		return atBase.isEmpty() ? inside : atBase;
	}

	/**
	 * A part's mean time per request at the base load, in milliseconds: the requests it
	 * held ÷ its arrivals over the base-load buckets (Little's law), else its mean
	 * latency over the run.
	 * @param node the part in the run
	 * @param buckets the base-load buckets
	 * @return the time; {@code null} when the run measured neither
	 */
	private static @Nullable Double duration(ReportNode node, List<Integer> buckets) {
		List<@Nullable Double> inflight = node.series("inflightMean");
		List<@Nullable Double> arrivals = node.series("arrivalRps");
		if (!inflight.isEmpty() && !arrivals.isEmpty()) {
			double held = sum(at(inflight, buckets));
			double rate = sum(at(arrivals, buckets));
			if (rate > 0) {
				return held / rate * MILLIS_PER_SECOND;
			}
		}
		LatencyStats latency = node.summary().latencyMs();
		return (latency != null) ? latency.mean() : null;
	}

	/**
	 * A part's share of reads: its static load's, else the workload's.
	 * @param load the part's static load at the base rate; {@code null} for none
	 * @param workload the workload's read share
	 * @return the share
	 */
	private static double readShare(@Nullable NodeLoad load, double workload) {
		return (load != null && load.total() > 0) ? load.read() / load.total() : workload;
	}

	/**
	 * A series' values in some buckets, a gap read as zero.
	 * @param series the series
	 * @param buckets the buckets' indexes; those past the series' end are skipped
	 * @return the values, in the buckets' order
	 */
	private static List<Double> at(List<@Nullable Double> series, List<Integer> buckets) {
		List<Double> values = new ArrayList<>();
		for (int bucket : buckets) {
			if (bucket < series.size()) {
				values.add(Objects.requireNonNullElse(series.get(bucket), 0.0));
			}
		}
		return values;
	}

	private static double mean(List<Double> values) {
		return sum(values) / values.size();
	}

	private static double sum(List<Double> values) {
		return FloatSum.of(values.stream().mapToDouble(Double::doubleValue));
	}

}
