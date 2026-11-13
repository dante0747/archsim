package io.archsim.benchmarks.inputs;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * A query's time inside PostgreSQL and the rest of its round trip: PostgreSQL's CPU per
 * query (calibration on 31 Oct 2026 found the backends busy on the CPU for nearly all of
 * it), shaped like the round trip the service measured and never longer than it; the rest
 * is the service-to-database link, split into two legs that keep the round trip's mean
 * and variance.
 */
final class ServerTimes {

	/** A leg shorter than this is none. */
	private static final double LEAST_LEG_MS = 0.001;

	private static final int DECIMALS = 4;

	private ServerTimes() {
	}

	/**
	 * A query's time in PostgreSQL.
	 * @param cpuMs the CPU PostgreSQL spent per query, or {@code null} without a usable
	 * figure
	 * @param roundTrip the round trip the service measured: its median and p95
	 * @return the CPU shaped like the round trip; without a CPU figure the round trip
	 */
	static ObjectNode serverTime(@Nullable Double cpuMs, JsonNode roundTrip) {
		ObjectNode whole = ModelDistributions.lognormal(roundTrip.get("median").doubleValue(),
				roundTrip.get("p95").doubleValue());
		if (cpuMs == null) {
			return whole;
		}
		return ModelDistributions.lognormalWithMean(Math.min(cpuMs, ModelDistributions.mean(whole)), roundTrip);
	}

	/**
	 * How a query's time in PostgreSQL was found.
	 * @param what the query
	 * @param cpuMs the CPU PostgreSQL spent per query, or {@code null}
	 * @param roundTripWords the round trip, in words
	 * @param note what to add after the CPU figure
	 * @return the words
	 */
	static String how(String what, @Nullable Double cpuMs, String roundTripWords, String note) {
		if (cpuMs == null) {
			return "no usable PostgreSQL CPU per " + what + " in the pass: " + roundTripWords
					+ " as the service measured it";
		}
		return "PostgreSQL's CPU per " + what + " (" + PythonFormat.format(cpuMs, ".4f") + " ms" + note
				+ "), shaped like " + roundTripWords;
	}

	/**
	 * One way of the service-to-database link: the round trip less the server's time, in
	 * two equal legs that keep the round trip's mean and variance (each a lognormal of
	 * half the mean and half the variance, Fenton-Wilkinson), or 0 when nothing is left.
	 * @param roundTrip the round trip: its median and p95
	 * @param server the server's time
	 * @return the leg, a constant or a distribution
	 */
	static JsonNode linkLeg(JsonNode roundTrip, JsonNode server) {
		ModelDistributions.Moments trip = ModelDistributions.moments(ModelDistributions
			.lognormal(roundTrip.get("median").doubleValue(), roundTrip.get("p95").doubleValue()));
		ModelDistributions.Moments inside = ModelDistributions.moments(server);
		double mean = (trip.mean() - inside.mean()) / 2;
		double variance = Math.max(0.0, trip.variance() - inside.variance()) / 2;
		if (mean < LEAST_LEG_MS) {
			return PythonValues.number(0.0);
		}
		if (variance <= 0) {
			return PythonValues.number(PythonRound.round(mean, DECIMALS));
		}
		double logVariance = StrictMath.log(1 + variance / (mean * mean));
		double median = mean / StrictMath.exp(logVariance / 2);
		return ModelDistributions.lognormal(median,
				median * StrictMath.exp(ModelDistributions.Z95 * StrictMath.sqrt(logVariance)));
	}

	/**
	 * A link latency in words.
	 * @param leg a leg, a constant or a distribution
	 * @return the words
	 */
	static String describe(JsonNode leg) {
		if (leg.isObject()) {
			return "median " + PythonFormat.format(leg.get("median").doubleValue(), ".4f") + ", p95 "
					+ PythonFormat.format(leg.get("p95").doubleValue(), ".4f") + " ms";
		}
		return PythonFormat.format(leg.doubleValue(), ".4f") + " ms";
	}

	/**
	 * The words of a list of queries: {@code 100 reads at 0.500 ms and 10 writes ...}.
	 * @param parts each kind of query
	 * @return the words
	 */
	static String mix(List<String> parts) {
		return String.join(" and ", parts);
	}

}
