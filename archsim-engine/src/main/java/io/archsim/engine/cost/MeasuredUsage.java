package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.LatencyStats;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

/**
 * What each part handles at a load of the workload after a run: its rate measured in the
 * run's time buckets at that load (the whole window when no bucket is at it), scaled to
 * the load exactly, so fan-out, cache misses and retries are kept; split into reads and
 * writes as the static load estimate splits it; and the replicas it keeps busy there.
 */
final class MeasuredUsage {

	private static final double MILLIS_PER_SECOND = 1000.0;

	private MeasuredUsage() {
	}

	/**
	 * Each part's usage at a load, measured in a run.
	 * @param document the design the run simulated
	 * @param loads the load estimator, for each part's share of reads
	 * @param report the run's report
	 * @param level the load
	 * @return the run's load: each part's usage by its id, for the parts with an arrival
	 * rate
	 * @throws IllegalArgumentException when the report lists no parts: a run's report
	 * lists every part of its design, and a design has a client
	 */
	static LevelLoad.Measured of(ArchitectureDocument document, LoadEstimator loads, RunReport report,
			LoadLevel level) {
		if (report.nodes().isEmpty()) {
			throw new IllegalArgumentException("a run's report lists the parts of the design it ran");
		}
		Workload workload = document.workload();
		Window window = Window.of(report, level, level.rate(workload));
		Map<String, @Nullable NodeLoad> estimated = EstimatedUsage.at(document, loads, level);
		double workloadReads = Math.min(1.0, Math.max(0.0, workload.readRatio()));
		Map<String, Usage> usage = new HashMap<>();
		Map<String, Double> busy = new HashMap<>();
		for (ReportNode node : report.nodes()) {
			Double arrivals = node.summary().arrivalRps();
			if (arrivals != null) {
				double rps = window.rate(node, arrivals);
				double share = readShare(estimated.get(node.id()), workloadReads);
				usage.put(node.id(), new Usage(rps, rps * share, rps * (1 - share), duration(node, window.buckets())));
				Double perRequest = busyPerRequest(node.summary());
				if (perRequest != null) {
					busy.put(node.id(), perRequest * rps);
				}
			}
		}
		return new LevelLoad.Measured(level, usage, busy, new LevelLoad.ScaledRun(report, window.scale()));
	}

	/**
	 * The replicas a part kept busy per request it received: the larger of its slots' and
	 * its CPU's utilization, times its replicas on average, as an HPA reads them.
	 * @param summary the part's summary
	 * @return the busy replicas per request; {@code null} for a part that did not scale
	 */
	private static @Nullable Double busyPerRequest(NodeSummary summary) {
		Double utilization = summary.utilization();
		Double replicas = summary.replicasMean();
		Double arrivals = summary.arrivalRps();
		if (utilization == null || replicas == null || arrivals == null || arrivals <= 0) {
			return null;
		}
		double cores = Objects.requireNonNullElse(summary.cpuCoreUtilization(), 0.0);
		return Math.max(utilization, cores) * replicas / arrivals;
	}

	/**
	 * A part's mean time per request at the load, in milliseconds: the requests it held ÷
	 * its arrivals over the load's buckets (Little's law), else its mean latency over the
	 * run.
	 * @param node the part in the run
	 * @param buckets the load's buckets
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
	 * @param load the part's static load at the load's rate; {@code null} for none
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

	/**
	 * The run's time buckets at a load, and how a part's rate in them is scaled to it.
	 *
	 * @param buckets the buckets' indexes, in order
	 * @param rate the load's rate
	 * @param offered the run's mean offered rate in those buckets; zero for none
	 * @param scale the load's rate ÷ the run's offered load
	 */
	private record Window(List<Integer> buckets, double rate, double offered, double scale) {

		/**
		 * The run's buckets inside its window whose offered rate is at the load; all of
		 * those inside its window when none is.
		 * @param report the run's report
		 * @param level the load
		 * @param rate the load's rate
		 * @return the buckets
		 */
		static Window of(RunReport report, LoadLevel level, double rate) {
			List<@Nullable Double> times = report.timeseries().t();
			List<@Nullable Double> offered = report.timeseries().offeredRps();
			List<Integer> inside = new ArrayList<>();
			List<Integer> atLevel = new ArrayList<>();
			for (int i = 0; i < Math.min(times.size(), offered.size()); i++) {
				// a bucket without its time counts as outside the window;
				// the simulator writes every bucket's time
				Double time = times.get(i);
				if (time != null && report.window().warmupS() <= time && time < report.window().durationS()) {
					inside.add(i);
					if (level.holds(Objects.requireNonNullElse(offered.get(i), 0.0), rate)) {
						atLevel.add(i);
					}
				}
			}
			List<Integer> buckets = atLevel.isEmpty() ? inside : atLevel;
			List<Double> offeredThere = at(offered, buckets);
			Double runOffered = report.summary().offeredRps();
			return new Window(buckets, rate, offeredThere.isEmpty() ? 0.0 : mean(offeredThere),
					(runOffered != null && runOffered > 0) ? rate / runOffered : 1.0);
		}

		/**
		 * A part's rate at the load: its mean in the buckets scaled to the load, else its
		 * rate over the run scaled.
		 * @param node the part in the run
		 * @param arrivals its rate over the run
		 * @return its rate at the load
		 */
		double rate(ReportNode node, double arrivals) {
			List<Double> seen = at(node.series("arrivalRps"), this.buckets);
			return (!seen.isEmpty() && this.offered > 0) ? mean(seen) * this.rate / this.offered
					: arrivals * this.scale;
		}

	}

}
