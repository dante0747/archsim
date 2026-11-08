package io.archsim.engine.reporting;

import java.util.OptionalDouble;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;

/**
 * A part's time series in a report, one value per bucket up to where the run stopped.
 */
final class NodeSeries {

	private final NodeMetrics metrics;

	private final Timeline timeline;

	private final int used;

	NodeSeries(RuntimeComponent component, int used) {
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
		OptionalDouble slots = Capacity.slots(this.metrics);
		if (slots.isPresent()) {
			series.set("utilization", utilization(slots.getAsDouble() * this.timeline.bucketMs()));
		}
		series.set("inflightMean", Figures.roundedSeries(this.metrics.inflight().meanSeries(), this.used));
		series.set("latencyP50Ms",
				Figures.series(this.metrics.residence().percentileSeries(Percentiles.MEDIAN), this.used));
		series.set("latencyP95Ms",
				Figures.series(this.metrics.residence().percentileSeries(Percentiles.P95), this.used));
		return series;
	}

	/**
	 * The highest utilization of a bucket inside the measurement window.
	 * @param series the part's series
	 * @return the peak, rounded, or a JSON {@code null} without a utilization series
	 */
	JsonNode peakUtilization(ObjectNode series) {
		JsonNode utilization = series.path("utilization");
		int first = (int) Math.ceil(this.timeline.windowStart() / this.timeline.bucketMs());
		int last = Math.max(first + 1, (int) Math.floor(this.timeline.windowEnd() / this.timeline.bucketMs()));
		OptionalDouble peak = OptionalDouble.empty();
		for (int bucket = first; bucket < Math.min(last, utilization.size()); bucket++) {
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

	private ArrayNode utilization(double slotTime) {
		double[] areas = this.metrics.busy().area();
		double[] ratios = new double[areas.length];
		for (int bucket = 0; bucket < ratios.length; bucket++) {
			ratios[bucket] = areas[bucket] / slotTime;
		}
		return Figures.roundedSeries(ratios, this.used);
	}

}
