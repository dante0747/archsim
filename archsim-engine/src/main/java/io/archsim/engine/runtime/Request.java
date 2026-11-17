package io.archsim.engine.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.RequestType;

/**
 * A request in flight: what it asks and when its client sent it, the call depth it has
 * reached, whether its client gave up on it, and for a sampled request the steps it took.
 * Each client attempt is a request of its own, so work the client abandoned can be told
 * apart; the attempts of one user request share its trace.
 */
public final class Request {

	/** The most steps a trace keeps. */
	private static final int MAX_SPANS = 400;

	private final Arrival arrival;

	private final @Nullable List<Span> trace;

	private boolean abandoned;

	private int depth;

	/**
	 * When each part that refused this request first refused it, by the part's id: a
	 * cache counts a read it refused once, however often the read is retried, and takes
	 * the count back when it answers a later attempt (ADR-0082). {@code null} until a
	 * part refuses it, and collected with the request.
	 */
	private @Nullable Map<String, Double> refusals;

	private Request(Arrival arrival, @Nullable List<Span> trace) {
		this.arrival = arrival;
		this.trace = trace;
	}

	/**
	 * A user request as it arrives, its steps recorded.
	 * @param arrival what it asks and when
	 * @return the request, at depth 0
	 */
	public static Request withTrace(Arrival arrival) {
		return new Request(arrival, new ArrayList<>());
	}

	/**
	 * A user request as it arrives, its steps not recorded.
	 * @param arrival what it asks and when
	 * @return the request, at depth 0
	 */
	public static Request withoutTrace(Arrival arrival) {
		return new Request(arrival, null);
	}

	/**
	 * A new attempt of this request: the same arrival and trace, from depth 0, not given
	 * up on.
	 * @return the attempt
	 */
	public Request attempt() {
		return new Request(this.arrival, this.trace);
	}

	/**
	 * The request a message queue's consumer handles for a message this request
	 * published: the same user request and trace, sent when the message was published, at
	 * the depth it was published at, not given up on.
	 * @param publishedAt when the message was published
	 * @param depth the call depth it was published at
	 * @return the consumer's request
	 */
	public Request delivered(double publishedAt, int depth) {
		Arrival published = new Arrival(this.arrival.id(), this.arrival.operation(), this.arrival.key(), publishedAt,
				this.arrival.measured());
		Request delivered = new Request(published, this.trace);
		delivered.depth = depth;
		return delivered;
	}

	/**
	 * Notes that a part refused this request, unless it refused it before.
	 * @param partId the part
	 * @param time when it refused it
	 * @return {@code true} when the part had not refused it yet
	 */
	public boolean addRefusal(String partId, double time) {
		if (this.refusals == null) {
			this.refusals = new HashMap<>();
		}
		return this.refusals.putIfAbsent(partId, time) == null;
	}

	/**
	 * Forgets that a part refused this request, as it answered it after all.
	 * @param partId the part
	 * @return when the part first refused it; empty when it had not refused it
	 */
	public OptionalDouble removeRefusal(String partId) {
		Double time = (this.refusals != null) ? this.refusals.remove(partId) : null;
		return (time != null) ? OptionalDouble.of(time) : OptionalDouble.empty();
	}

	/**
	 * What the request asks and when it was sent.
	 * @return the arrival
	 */
	public Arrival arrival() {
		return this.arrival;
	}

	/**
	 * Whether the request reads or writes.
	 * @return the operation
	 */
	public RequestType operation() {
		return this.arrival.operation();
	}

	/**
	 * Whether the client gave up on this attempt.
	 * @return {@code true} once given up on
	 */
	public boolean abandoned() {
		return this.abandoned;
	}

	/**
	 * Marks the attempt as given up on by its client; work finished after this is wasted.
	 */
	public void abandon() {
		this.abandoned = true;
	}

	/**
	 * The call depth of the hop now handling the request.
	 * @return 0 at the client, one more for each link crossed
	 */
	public int depth() {
		return this.depth;
	}

	/**
	 * Moves the request to a call depth, as it enters or leaves a hop.
	 * @param depth the depth
	 */
	public void moveTo(int depth) {
		this.depth = depth;
	}

	/**
	 * Whether the request's steps are recorded.
	 * @return {@code true} for a sampled request
	 */
	public boolean traced() {
		return this.trace != null;
	}

	/**
	 * The steps recorded so far.
	 * @return the steps, in the order they were recorded; empty for a request not traced
	 */
	public List<Span> spans() {
		return (this.trace != null) ? Collections.unmodifiableList(this.trace) : List.of();
	}

	/**
	 * Records a step at a part, at the request's depth (nothing for a request not traced,
	 * or past 400 steps).
	 * @param nodeId the part
	 * @param kind what the step was
	 * @param interval when it started and ended
	 * @param detail what to say about it
	 */
	public void span(@Nullable String nodeId, SpanKind kind, Interval interval, @Nullable String detail) {
		span(new Span(nodeId, null, kind, interval.start(), interval.end(), this.depth, detail));
	}

	/**
	 * Records a step (nothing for a request not traced, or past 400 steps).
	 * @param span the step
	 */
	public void span(Span span) {
		if (this.trace != null && this.trace.size() < MAX_SPANS) {
			this.trace.add(span);
		}
	}

	/**
	 * What a request asks and when its client sent it.
	 *
	 * @param id the user request's number, from 1 in arrival order
	 * @param operation a read or a write
	 * @param key the key's rank in popularity, from 0
	 * @param sentAt when it was sent, in simulated milliseconds
	 * @param measured whether it arrived inside the measurement window
	 */
	public record Arrival(long id, RequestType operation, int key, double sentAt, boolean measured) {

	}

	/**
	 * When a step started and ended.
	 *
	 * @param start the start, in simulated milliseconds
	 * @param end the end
	 */
	public record Interval(double start, double end) {

	}

}
