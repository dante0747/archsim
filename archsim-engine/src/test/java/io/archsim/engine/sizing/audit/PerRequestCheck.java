package io.archsim.engine.sizing.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.cost.CostEstimator;
import io.archsim.engine.load.BaseRate;
import io.archsim.model.DesignDocuments;

/**
 * Each per-request cost line of a run against a hand calculation: the part's rate while
 * the offered load was at the base (from the run's own series), times the month, times
 * each list price; within 10%.
 */
final class PerRequestCheck {

	/** A per-request cost line is within this share of the hand calculation. */
	private static final double SLACK = 0.10;

	// a bucket whose offered load is at most this multiple of the base is at the base
	private static final double AT_BASE = 1.25;

	private static final double MILLION = 1e6;

	private static final double MS_PER_SECOND = 1000.0;

	private static final double MIB_PER_GIB = 1024.0;

	private final AuditedRun run;

	private final List<Integer> buckets;

	private final double base;

	private final double offered;

	private final Map<String, @Nullable Double> rates;

	/**
	 * The check of a run.
	 * @param run the run
	 */
	PerRequestCheck(AuditedRun run) {
		JsonNode input = run.report().path("input");
		JsonNode offeredRps = run.report().path("timeseries").path("offeredRps");
		this.run = run;
		this.base = BaseRate.of(DesignDocuments.load(input).workload());
		List<Integer> window = run.window();
		List<Integer> calm = window.stream()
			.filter((bucket) -> offeredRps.path(bucket).doubleValue() <= this.base * AT_BASE)
			.toList();
		this.buckets = calm.isEmpty() ? window : calm;
		this.offered = mean(offeredRps);
		this.rates = TemplateRuns.rates(input.get("pricing"));
	}

	/**
	 * Each per-request line's check; DynamoDB's on-demand units, which depend on the
	 * read/write split, are left to {@code CostEstimatorTests} (its
	 * {@code cost/cases.json} has them).
	 * @return the checks
	 */
	List<AuditCheck> checks() {
		List<AuditCheck> checks = new ArrayList<>();
		for (JsonNode line : this.run.report().path("cost").path("byNode")) {
			JsonNode node = this.run.node(AuditJson.text(line.path("id")));
			if (AuditJson.is(line.path("unit"), "million") && AuditJson.truthy(line.path("monthly"))
					&& !node.isMissingNode()) {
				Hand hand = byHand(node, line);
				if (hand != null) {
					checks.add(check(line, hand));
				}
			}
		}
		return checks;
	}

	private AuditCheck check(JsonNode line, Hand hand) {
		double monthly = line.path("monthly").doubleValue();
		boolean ok = hand.monthly() == 0 || Math.abs(monthly - hand.monthly()) <= SLACK * hand.monthly();
		return new AuditCheck(AuditJson.text(line.path("id")), "per-request", ok,
				String.format(Locale.ROOT, "$%,.2f vs $%,.2f by hand (%s)", monthly, hand.monthly(), hand.how()));
	}

	// the line by hand; null for a DynamoDB table. The order decides a part that
	// is several of these: its type first, then an API gateway's or SQS's
	// technology, then a serverless platform, then DynamoDB's technology
	private @Nullable Hand byHand(JsonNode node, JsonNode line) {
		JsonNode config = this.run.config(AuditJson.text(line.path("id")));
		double rps = mean(node.path("timeseries").path("arrivalRps")) * this.base / this.offered;
		double month = rps * CostEstimator.SECONDS_PER_MONTH;
		String technology = AuditJson.text(config.path("technology"));
		String perSecond = String.format(Locale.ROOT, "%.1f req/s", rps);
		if (AuditJson.is(node.path("type"), "external_api")) {
			return new Hand(month * AuditJson.number(config.path("costPerMillionRequests"), 0.0) / MILLION,
					String.format(Locale.ROOT, "%.1f calls/s", rps));
		}
		if ("api_gateway".equals(technology)) {
			return new Hand(month * rate("apiGatewayPerMillion") / MILLION, perSecond);
		}
		if ("sqs".equals(technology)) {
			return new Hand(month * 3 * rate("queuePerMillionRequests") / MILLION,
					String.format(Locale.ROOT, "%.1f messages/s x 3 requests", rps));
		}
		if (AuditJson.is(config.path("platform"), "serverless")) {
			return serverlessByHand(node, config, rps, month);
		}
		if ("dynamodb".equals(technology)) {
			return null;
		}
		return new Hand(month * AuditJson.number(line.path("unitPrice"), 0.0) / MILLION, perSecond);
	}

	// a function: its invocations and its duration times its memory
	private Hand serverlessByHand(JsonNode node, JsonNode config, double rps, double month) {
		double came = sum(node.path("timeseries").path("arrivalRps"));
		double duration = (came != 0) ? sum(node.path("timeseries").path("inflightMean")) / came : 0.0;
		double gib = AuditJson.number(config.path("memoryGib"), 0.0);
		return new Hand(
				month * (rate("serverlessPerMillionRequests") / MILLION + duration * gib * rate("serverlessGbSecond")),
				String.format(Locale.ROOT, "%.1f invocations/s x %.1f ms x %,.0f MB", rps, duration * MS_PER_SECOND,
						gib * MIB_PER_GIB));
	}

	private double rate(String name) {
		return Objects.requireNonNullElse(this.rates.get(name), 0.0);
	}

	private double sum(JsonNode series) {
		return this.buckets.stream().mapToDouble((bucket) -> series.path(bucket).doubleValue()).sum();
	}

	private double mean(JsonNode series) {
		return sum(series) / this.buckets.size();
	}

	/**
	 * A cost line by hand.
	 *
	 * @param monthly its monthly cost
	 * @param how the rate it was worked out from
	 */
	private record Hand(double monthly, String how) {
	}

}
