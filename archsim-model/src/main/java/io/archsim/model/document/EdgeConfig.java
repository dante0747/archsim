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
public record EdgeConfig(@DefaultFrom("READS_AND_WRITES") @Description(REQUEST_TYPES) @Ui(label = "Request types",
		group = ROUTING) @MinItems(1) @UniqueItems("request types must be unique") List<RequestType> requestTypes,
		@DefaultFrom("HALF_A_MILLISECOND") @Description(LATENCY) @Ui(label = "Network latency (one way)", unit = "ms",
				group = "Network", assumption = true) Distribution latencyMs,
		@Description(TIMEOUT) @Ui(label = "Timeout", unit = "ms",
				group = RELIABILITY) @Gt(0) @Le(600_000) @Nullable Double timeoutMs,
		@Default("1") @Description(MAX_ATTEMPTS) @Ui(label = "Max attempts",
				group = RELIABILITY) @Ge(1) @Le(5) int maxAttempts,
		@Default("50.0") @Description(RETRY_BACKOFF) @Ui(label = "Retry backoff", unit = "ms",
				group = RELIABILITY) @Ge(0) @Le(60_000) double retryBackoffMs,
		@Default("0") @Description(ORDER) @Ui(label = "Call order", group = ROUTING) @Ge(0) @Le(1000) int order,
		@Default("false") @Description(READS_ON_MISS) @Ui(label = "Reads only on a cache miss",
				group = ROUTING) boolean readsOnMiss,
		@Default("false") @Description(CONTINUE_ON_FAILURE) @Ui(label = "Carry on if this call fails",
				group = RELIABILITY) boolean continueOnFailure) {

	static final String ROUTING = "Routing";

	static final String RELIABILITY = "Reliability";

	static final String REQUEST_TYPES = "Request types carried by this link. Used for routing (e.g. reads to "
			+ "cache, writes to DB).";

	static final String LATENCY = "One-way network latency in ms, applied to the request and again to the "
			+ "response.";

	static final String TIMEOUT = "Per-attempt time the caller waits before giving up (ms). Empty = wait "
			+ "indefinitely.";

	static final String MAX_ATTEMPTS = "Total attempts including retries (1 = no retries). Bounded to prevent "
			+ "retry storms from running away.";

	static final String RETRY_BACKOFF = "Base backoff before a retry (ms); doubles on each further retry.";

	static final String ORDER = "Call order when a service calls several dependencies for the same request "
			+ "(lower first).";

	static final String READS_ON_MISS = "Cache-aside: send reads over this link only after the service's "
			+ "cache lookup for the same request missed (or failed, when that link carries on after failures). The "
			+ "service then reads the data store itself and writes the value back to the cache. Writes on this "
			+ "link are always sent.";

	static final String CONTINUE_ON_FAILURE = "If this call still fails after its attempts, the service "
			+ "carries on instead of failing the request. A failed cache lookup counts as a miss, so reads go to the "
			+ "links set to 'Reads only on a cache miss'; a failed cache invalidation is skipped.";

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
