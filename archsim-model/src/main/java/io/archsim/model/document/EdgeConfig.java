package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.SchemaExtra;
import io.archsim.model.codec.UniqueItems;
import io.archsim.model.distribution.Distribution;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
public record EdgeConfig(
		@Default("[\"read\", \"write\"]") @Description("Request types carried by this link. Used for routing (e.g. reads to cache, writes to DB).") @SchemaExtra("{\"x-ui\": {\"label\": \"Request types\", \"group\": \"Routing\"}}") @MinItems(1) @UniqueItems("request types must be unique") List<RequestType> requestTypes,

		@Default("{\"kind\": \"constant\", \"value\": 0.5}") @Description("One-way network latency in ms, applied to the request and again to the response.") @SchemaExtra("""
				{"x-ui": {"label": "Network latency (one way)", "unit": "ms", "group": "Network",
				          "assumption": true}}""") Distribution latencyMs,

		@Description("Per-attempt time the caller waits before giving up (ms). Empty = wait indefinitely.") @SchemaExtra("{\"x-ui\": {\"label\": \"Timeout\", \"unit\": \"ms\", \"group\": \"Reliability\"}}") @Gt(0) @Le(600_000) @Nullable Double timeoutMs,

		@Default("1") @Description("Total attempts including retries (1 = no retries). Bounded to prevent retry storms from running away.") @SchemaExtra("{\"x-ui\": {\"label\": \"Max attempts\", \"group\": \"Reliability\"}}") @Ge(1) @Le(5) int maxAttempts,

		@Default("50.0") @Description("Base backoff before a retry (ms); doubles on each further retry.") @SchemaExtra("{\"x-ui\": {\"label\": \"Retry backoff\", \"unit\": \"ms\", \"group\": \"Reliability\"}}") @Ge(0) @Le(60_000) double retryBackoffMs,

		@Default("0") @Description("Call order when a service calls several dependencies for the same request (lower first).") @SchemaExtra("{\"x-ui\": {\"label\": \"Call order\", \"group\": \"Routing\"}}") @Ge(0) @Le(1000) int order,

		@Default("false") @Description("Cache-aside: send reads over this link only after the service's cache lookup for the same"
				+ " request missed (or failed, when that link carries on after failures). The service"
				+ " then reads the data store itself and writes the value back to the cache. Writes on"
				+ " this link are always sent.") @SchemaExtra("{\"x-ui\": {\"label\": \"Reads only on a cache miss\", \"group\": \"Routing\"}}") boolean readsOnMiss,

		@Default("false") @Description("If this call still fails after its attempts, the service carries on instead of failing the"
				+ " request. A failed cache lookup counts as a miss, so reads go to the links set to"
				+ " 'Reads only on a cache miss'; a failed cache invalidation is skipped.") @SchemaExtra("{\"x-ui\": {\"label\": \"Carry on if this call fails\", \"group\": \"Reliability\"}}") boolean continueOnFailure) {

	public EdgeConfig {
		requestTypes = requestTypes.stream().sorted().toList();
	}

	/** Whether the link carries {@code type}. */
	public boolean carries(RequestType type) {
		return requestTypes.contains(type);
	}
}
