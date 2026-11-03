package io.archsim.model.document;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.Ui;
import io.archsim.model.codec.UniqueItems;
import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Distribution;

/**
 * Behaviour of a request crossing a link from a caller to a callee.
 *
 * @param requestTypes the request types the link carries, sorted
 * @param latencyMs one-way network latency, applied to the request and again to the
 * response
 * @param timeoutMs how long the caller waits per attempt; {@code null} for as long as it
 * takes
 * @param maxAttempts attempts including retries
 * @param retryBackoffMs the backoff before the first retry, doubling with each further
 * one
 * @param order call order among a service's links for the same request, lower first
 * @param readsOnMiss cache-aside: reads use the link only after the cache lookup missed
 * @param continueOnFailure the service carries on when the call still fails
 */
@Description("Behaviour of a request crossing a link from a caller to a callee.")
public record EdgeConfig(@DefaultFrom("READS_AND_WRITES") @Description(Descriptions.EDGE_REQUEST_TYPES) @Ui(
		label = "Request types",
		group = Descriptions.EDGE_ROUTING_GROUP) @MinItems(1) @UniqueItems(Descriptions.EDGE_UNIQUE_REQUEST_TYPES) List<RequestType> requestTypes,
		@DefaultFrom("HALF_A_MILLISECOND") @Description(Descriptions.EDGE_LATENCY_MS) @Ui(
				label = "Network latency (one way)", unit = "ms", group = Descriptions.EDGE_NETWORK_GROUP,
				assumption = true) Distribution latencyMs,
		@Description(Descriptions.EDGE_TIMEOUT_MS) @Ui(label = "Timeout", unit = "ms",
				group = Descriptions.EDGE_RELIABILITY_GROUP) @Gt(0) @Le(600_000) @Nullable Double timeoutMs,
		@Default("1") @Description(Descriptions.EDGE_MAX_ATTEMPTS) @Ui(label = "Max attempts",
				group = Descriptions.EDGE_RELIABILITY_GROUP) @Ge(1) @Le(5) int maxAttempts,
		@Default("50.0") @Description(Descriptions.EDGE_RETRY_BACKOFF_MS) @Ui(label = "Retry backoff", unit = "ms",
				group = Descriptions.EDGE_RELIABILITY_GROUP) @Ge(0) @Le(60_000) double retryBackoffMs,
		@Default("0") @Description(Descriptions.EDGE_CALL_ORDER) @Ui(label = "Call order",
				group = Descriptions.EDGE_ROUTING_GROUP) @Ge(0) @Le(1000) int order,
		@Default("false") @Description(Descriptions.EDGE_READS_ON_MISS) @Ui(label = "Reads only on a cache miss",
				group = Descriptions.EDGE_ROUTING_GROUP) boolean readsOnMiss,
		@Default("false") @Description(Descriptions.EDGE_CONTINUE_ON_FAILURE) @Ui(label = "Carry on if this call fails",
				group = Descriptions.EDGE_RELIABILITY_GROUP) boolean continueOnFailure) {

	/** What a link carries by default: every request type. */
	static final List<RequestType> READS_AND_WRITES = List.of(RequestType.READ, RequestType.WRITE);

	/** A link's default network latency: half a millisecond, every time. */
	static final Distribution HALF_A_MILLISECOND = new Constant(0.5);

	/**
	 * Sorts the request types, so equal links have equal configs.
	 */
	public EdgeConfig {
		requestTypes = requestTypes.stream().sorted().toList();
	}

	/**
	 * Whether the link carries {@code type}.
	 * @param type a request type
	 * @return {@code true} when requests of the type use the link
	 */
	public boolean carries(RequestType type) {
		return this.requestTypes.contains(type);
	}

}
