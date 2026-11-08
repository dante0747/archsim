package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;

/**
 * A part's time series in a report, one value per bucket up to where the run stopped.
 */
final class NodeSeries {

	private final RuntimeComponent component;

	private final NodeMetrics metrics;

	private final Timeline timeline;

	private final int used;

	NodeSeries(RuntimeComponent component, int used) {
		this.component = component;
		this.metrics = component.metrics();
		this.timeline = this.metrics.timeline();
		this.used = used;
	}

	ObjectNode write() {
		ObjectNode series = Figures.NODES.objectNode();
		series.set("arrivalRps", Figures.roundedSeries(this.metrics.arrivals().rateSeries(), this.used));
		series.set("completedRps", Figures.roundedSeries(this.metrics.completed().rateSeries(), this.used));
		series.set("rejectedRps", Figures.roundedSeries(rejectedRates(), this.used));
		series.set("failedRps", Figures.roundedSeries(this.metrics.failedDownstream().rateSeries(), this.used));
		series.set("queueMean", Figures.roundedSeries(this.metrics.queue().meanSeries(), this.used));
		series.set("queueMax", Figures.countSeries(this.metrics.queue().maxSeries(), this.used));
		series.set("queueWaitMs", Figures.series(this.metrics.queueWait().series(), this.used));
		writeUtilization(series);
		// the part's own series; a series it shares with every part keeps its place
		for (Map.Entry<String, List<@Nullable Double>> extra : this.component.extraTimeseries().entrySet()) {
			series.set(extra.getKey(), Figures.roundedSeries(extra.getValue(), this.used));
		}
		series.set("inflightMean", Figures.roundedSeries(this.metrics.inflight().meanSeries(), this.used));
		series.set("latencyP50Ms",
				Figures.series(this.metrics.residence().percentileSeries(Percentiles.MEDIAN), this.used));
		series.set("latencyP95Ms",
				Figures.series(this.metrics.residence().percentileSeries(Percentiles.P95), this.used));
		HitRatio.of(this.metrics).ifPresent((lookups) -> series.set("hitRatio", lookups.series(this.used)));
		return series;
	}

	// the busy slots over the slots, or, when they change during the run, over the slots
	// available in each bucket
	private void writeUtilization(ObjectNode series) {
		Optional<Gauge> capacity = this.component.capacityGauge();
		OptionalDouble slots = Capacity.slots(this.component);
		if (capacity.isPresent()) {
			double[] available = capacity.get().area();
			series.set("utilization", ratios(this.component.occupancy().area(), available));
			if (this.component.splitsCpu()) {
				series.set("cpuUtilization", ratios(this.metrics.cpu().area(), available));
			}
		}
		else if (slots.isPresent()) {
			double slotTime = slots.getAsDouble() * this.timeline.bucketMs();
			series.set("utilization", shares(this.component.occupancy().area(), slotTime));
			if (this.component.splitsCpu()) {
				series.set("cpuUtilization", shares(this.metrics.cpu().area(), slotTime));
			}
		}
	}

	/**
	 * The highest utilization of a bucket inside the measurement window.
	 * @param series the part's series
	 * @return the peak, rounded, or a JSON {@code null} without a utilization series
	 */
	JsonNode peakUtilization(ObjectNode series) {
		JsonNode utilization = series.path("utilization");
		int last = Math.min(this.timeline.windowBucketsEnd(), utilization.size());
		OptionalDouble peak = OptionalDouble.empty();
		for (int bucket = this.timeline.firstWindowBucket(); bucket < last; bucket++) {
			JsonNode value = utilization.get(bucket);
			if (!value.isNull() && (peak.isEmpty() || value.asDouble() > peak.getAsDouble())) {
				peak = OptionalDouble.of(value.asDouble());
			}
		}
		return Figures.rounded(peak);
	}

	private double[] rejectedRates() {
		double[] sum = new double[this.timeline.buckets()];
		for (Counter rejected : this.metrics.rejected().values()) {
			double[] rates = rejected.rateSeries();
			for (int bucket = 0; bucket < sum.length; bucket++) {
				sum[bucket] += rates[bucket];
			}
		}
		return sum;
	}

	// each bucket's busy slot-time over its available slot-time
	private ArrayNode ratios(double[] busy, double[] available) {
		List<@Nullable Double> ratios = new ArrayList<>(busy.length);
		for (int bucket = 0; bucket < busy.length; bucket++) {
			ratios.add((available[bucket] != 0) ? busy[bucket] / available[bucket] : null);
		}
		return Figures.roundedSeries(ratios, this.used);
	}

	private ArrayNode shares(double[] areas, double slotTime) {
		double[] ratios = new double[areas.length];
		for (int bucket = 0; bucket < ratios.length; bucket++) {
			ratios[bucket] = areas[bucket] / slotTime;
		}
		return Figures.roundedSeries(ratios, this.used);
	}

}
