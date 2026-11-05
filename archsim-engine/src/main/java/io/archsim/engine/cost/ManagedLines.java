package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.cost.SnapshotLines.Billing;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.pricing.PriceSnapshot;

/**
 * Managed and per-request parts at a price snapshot's rates: a managed load balancer per
 * hour plus its capacity units, an API gateway per request, a serverless function per
 * invocation and GB-second, a managed queue per request, and DynamoDB
 * ({@link DynamoDbLine}).
 */
final class ManagedLines {

	/** Billed per request, which a snapshot may not price. */
	static final Set<String> PER_REQUEST = Set.of("api_gateway");

	/** Billed per hour at the snapshot's {@code loadBalancerHour}. */
	private static final Set<String> LOAD_BALANCERS = Set.of("aws_alb");

	/** Billed per request at the snapshot's {@code queuePerMillionRequests}. */
	private static final Set<String> QUEUES = Set.of("sqs", "pubsub");

	/** SQS bills each API call: a message is sent, received and deleted. */
	private static final int SQS_REQUESTS_PER_MESSAGE = 3;

	/** Illustrative: what a load balancer processes per request, in KB. */
	private static final double ALB_KB_PER_REQUEST = 10.0;

	/** A load balancer's capacity unit covers this many GB processed an hour. */
	private static final double ALB_GB_PER_LCU_HOUR = 1.0;

	private static final double SECONDS_PER_HOUR = 3600;

	private static final double KB_PER_GB = 1e6;

	private static final double MILLIS_PER_SECOND = 1000;

	private final PriceSnapshot snapshot;

	private final SnapshotLines lines;

	private final Map<String, @Nullable Double> rates;

	private final DynamoDbLine dynamoDb;

	ManagedLines(PriceSnapshot snapshot, DynamoDbLine dynamoDb) {
		this.snapshot = snapshot;
		this.lines = new SnapshotLines(snapshot.snapshotDate());
		this.rates = snapshot.rates();
		this.dynamoDb = dynamoDb;
	}

	/**
	 * A part's line at the snapshot's rates.
	 * @param part the part
	 * @param use its usage at the base load; {@code null} when unknown before a run
	 * @param keyCount the workload's keys, for a table's size
	 * @return the line; empty when the snapshot has no rate for the part, which is then
	 * priced like any other part
	 */
	Optional<CostLine> line(CostPart part, @Nullable Usage use, double keyCount) {
		if (this.snapshot.illustrative()) {
			return Optional.empty();
		}
		boolean serverless = ServiceSpec.TYPE.equals(part.type()) && part.writtenIs("platform", "serverless");
		return rate(part.technologyIn(LOAD_BALANCERS), "loadBalancerHour")
			.map((hourly) -> loadBalancer(part, use, hourly))
			.or(() -> rate(part.technologyIn(PER_REQUEST), "apiGatewayPerMillion")
				.map((perMillion) -> gateway(part, use, perMillion)))
			.or(() -> rate(serverless, "serverlessGbSecond").map((gbSecond) -> function(part, use, gbSecond)))
			.or(() -> rate(part.technologyIn(QUEUES), "queuePerMillionRequests")
				.map((perMillion) -> queue(part, use, perMillion)))
			.or(() -> this.dynamoDb.line(part, use, keyCount));
	}

	/**
	 * A rate of the snapshot's, for a part it applies to.
	 * @param applies whether the rate applies to the part
	 * @param name the rate's name
	 * @return the rate; empty when it does not apply or the snapshot does not have it
	 */
	private Optional<Double> rate(boolean applies, String name) {
		return applies ? Optional.ofNullable(this.rates.get(name)) : Optional.empty();
	}

	private CostLine loadBalancer(CostPart part, @Nullable Usage use, double hourly) {
		List<CostItem> items = new ArrayList<>(
				List.of(CostItem.of("load balancer-hours", CostEstimator.HOURS_PER_MONTH, hourly, "hour")));
		String basis = "1 load balancer";
		Double lcuHour = this.rates.get("loadBalancerLcuHour");
		if (lcuHour != null && use != null) {
			double lcus = use.rps() * ALB_KB_PER_REQUEST * SECONDS_PER_HOUR / KB_PER_GB / ALB_GB_PER_LCU_HOUR;
			items.add(CostItem.of("LCU-hours", lcus * CostEstimator.HOURS_PER_MONTH, lcuHour, "LCU-hour"));
			basis += " + " + HalfUp.fixed(lcus, 2) + " capacity units (" + HalfUp.fixed(use.rps(), 1)
					+ " req/s at the base load × " + NumberText.plain(ALB_KB_PER_REQUEST)
					+ " KB processed each, assumed; 1 LCU per " + NumberText.plain(ALB_GB_PER_LCU_HOUR)
					+ " GB an hour)";
		}
		else if (lcuHour != null) {
			basis += " (capacity units: run a simulation to price them)";
		}
		else {
			basis += " (capacity units not included)";
		}
		double total = CostItem.total(items);
		return this.lines.line(part, new Billing(1, total / CostEstimator.HOURS_PER_MONTH, CostUnit.HOUR, total), basis,
				items);
	}

	private CostLine gateway(CostPart part, @Nullable Usage use, double perMillion) {
		if (use == null) {
			return perRequest(part, List.of(), "per request (run a simulation to price it)", null);
		}
		double requests = use.rps() * CostEstimator.SECONDS_PER_MONTH;
		return perRequest(part, List.of(CostItem.perMillion("requests", requests, perMillion, "request")),
				HalfUp.fixed(use.rps(), 1) + " req/s at the base load", requests);
	}

	/**
	 * A serverless function: per invocation, and per GB-second of its duration (its time
	 * in the part, waiting on its own calls included), at the base load.
	 * @param part the function
	 * @param use its usage at the base load
	 * @param gbSecond the price of a GB-second
	 * @return its line
	 */
	private CostLine function(CostPart part, @Nullable Usage use, double gbSecond) {
		Double memory = part.writtenNumber("memoryGib");
		if (use == null || memory == null) {
			return perRequest(part, List.of(), "per invocation (run a simulation to price it)", null);
		}
		Double duration = use.durationMs();
		String ownOnly = "";
		if (duration == null) {
			// its calls have no estimate (one waits on an overloaded part): its own time
			// only
			duration = LenientSettings.of(part.node().config()).meanMs("serviceTimeMs");
			ownOnly = ", its own time only";
		}
		double calls = use.rps() * CostEstimator.SECONDS_PER_MONTH;
		Double perInvocation = this.rates.get("serverlessPerMillionRequests");
		List<CostItem> items = List.of(
				CostItem.perMillion("invocations", calls,
						(perInvocation != null && perInvocation != 0) ? perInvocation : 0.0, "invocation"),
				CostItem.of("GB-seconds", calls * duration / MILLIS_PER_SECOND * memory, gbSecond, "GB-second"));
		return perRequest(part, items, HalfUp.fixed(use.rps(), 1) + " invocations/s at the base load × "
				+ HalfUp.fixed(duration, 1) + " ms" + ownOnly + " × " + CostText.megabytes(memory), calls);
	}

	private CostLine queue(CostPart part, @Nullable Usage use, double perMillion) {
		if (use == null) {
			return perRequest(part, List.of(), "per request (run a simulation to price it)", null);
		}
		int each = "sqs".equals(part.technology()) ? SQS_REQUESTS_PER_MESSAGE : 1;
		double requests = use.rps() * each * CostEstimator.SECONDS_PER_MONTH;
		String how = (each == SQS_REQUESTS_PER_MESSAGE) ? " × 3 requests (send, receive, delete)" : "";
		return perRequest(part, List.of(CostItem.perMillion("requests", requests, perMillion, "request")),
				HalfUp.fixed(use.rps(), 1) + " messages/s at the base load" + how, requests);
	}

	private CostLine perRequest(CostPart part, List<CostItem> items, String basis, @Nullable Double requests) {
		double total = CostItem.total(items);
		Double unitPrice = (requests != null && requests != 0) ? total / requests * CostItem.MILLION : null;
		return this.lines.line(part, new Billing(0, unitPrice, CostUnit.MILLION, total), basis, items);
	}

}
