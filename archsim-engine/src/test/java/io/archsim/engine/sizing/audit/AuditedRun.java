package io.archsim.engine.sizing.audit;

import java.util.List;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * A template's run as the audit reads it: the report, as JSON.
 *
 * @param report the run's report
 */
record AuditedRun(JsonNode report) {

	/**
	 * The run's parts.
	 * @return the report's nodes
	 */
	JsonNode nodes() {
		return this.report.path("nodes");
	}

	/**
	 * A part of the run.
	 * @param id the part's id
	 * @return its node; missing when the run has none by that id
	 */
	JsonNode node(String id) {
		for (JsonNode node : nodes()) {
			if (AuditJson.is(node.path("id"), id)) {
				return node;
			}
		}
		return MissingNode.getInstance();
	}

	/**
	 * A part's auto-sizing.
	 * @param id the part's id
	 * @return its sizing entry; missing when it has none
	 */
	JsonNode sizing(String id) {
		return this.report.path("sizing").path("nodes").path(id);
	}

	/**
	 * A part's config as the run's input has it.
	 * @param id the part's id
	 * @return its config; missing when the input has no such part
	 */
	JsonNode config(String id) {
		for (JsonNode node : this.report.path("input").path("architecture").path("nodes")) {
			if (AuditJson.is(node.path("id"), id)) {
				return node.path("config");
			}
		}
		return MissingNode.getInstance();
	}

	/**
	 * The run's monthly cost.
	 * @return the cost, in dollars a month
	 */
	double monthly() {
		return this.report.path("cost").path("monthly").doubleValue();
	}

	/**
	 * The buckets of the measured window, the warm-up left out.
	 * @return the buckets' indexes
	 */
	List<Integer> window() {
		JsonNode times = this.report.path("timeseries").path("t");
		double from = this.report.path("window").path("warmupS").doubleValue();
		double to = this.report.path("window").path("durationS").doubleValue();
		return IntStream.range(0, times.size())
			.filter((bucket) -> from <= times.path(bucket).doubleValue() && times.path(bucket).doubleValue() < to)
			.boxed()
			.toList();
	}

	/**
	 * Whether a part's resource is auto-sized: it has a sizing entry, not skipped, whose
	 * spec for the resource came from auto-sizing.
	 * @param entry the part's sizing entry
	 * @param resource the resource: {@code cpuCores} or {@code memoryGib}
	 * @return whether it is auto-sized
	 */
	static boolean autoSized(JsonNode entry, String resource) {
		return !entry.isMissingNode() && !AuditJson.truthy(entry.path("skipped"))
				&& AuditJson.is(entry.path("specs").path(resource).path("source"), "auto");
	}

	/**
	 * A series' values in some buckets.
	 * @param series the series
	 * @param buckets the buckets
	 * @return its values there, those it lacks or has as null left out
	 */
	static List<Double> values(JsonNode series, List<Integer> buckets) {
		return buckets.stream()
			.filter((bucket) -> series.path(bucket).isNumber())
			.map((bucket) -> series.path(bucket).doubleValue())
			.toList();
	}

	/**
	 * The p95 of values as the audit takes it: the value at the 95% rank, rounded up.
	 * @param values the values
	 * @return the p95; 0 without values
	 */
	static double p95(List<Double> values) {
		if (values.isEmpty()) {
			return 0.0;
		}
		List<Double> sorted = values.stream().sorted().toList();
		int rank = (int) Math.ceil(0.95 * sorted.size()) - 1;
		return sorted.get(Math.min(sorted.size() - 1, Math.max(0, rank)));
	}

	/**
	 * A part's instances in a bucket: a service's replicas (as it scaled), a cache's
	 * shards, a database's nodes; one for anything else.
	 * @param node the part's node in the report
	 * @param config the part's config
	 * @param bucket the bucket
	 * @return its instances
	 */
	static double instances(JsonNode node, JsonNode config, int bucket) {
		return switch (AuditJson.text(node.path("type"))) {
			case "service" -> {
				JsonNode replicas = node.path("timeseries").path("replicas").path(bucket);
				yield AuditJson.truthy(replicas) ? replicas.doubleValue()
						: AuditJson.number(config.path("replicas"), 1.0);
			}
			case "cache" -> AuditJson.number(config.path("shards"), 1.0);
			case "database" -> AuditJson.number(config.path("shards"), 1.0)
					* Math.max(AuditJson.number(config.path("replicationFactor"), 1.0),
							1 + AuditJson.number(config.path("readReplicas"), 0.0));
			default -> 1.0;
		};
	}

	/**
	 * The most memory a part needs.
	 * @param need the part's needs
	 * @return {@code memoryGibMax} where the need has it, else {@code memoryGib}
	 */
	static JsonNode memoryNeed(JsonNode need) {
		return need.has("memoryGibMax") ? need.path("memoryGibMax") : need.path("memoryGib");
	}

}
