package io.archsim.engine.runtime;

import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Promise;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.report.FailureReason;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.RequestType;
import io.archsim.model.text.PythonFormat;

/**
 * How a part calls another across a link: the request's network latency out, an outage
 * planned on the callee, the callee's handling, the latency back, and the caller giving
 * up after its timeout while the callee keeps working (the work is wasted, as in
 * reality).
 */
public final class Calls {

	private final SimContext context;

	Calls(SimContext context) {
		this.context = context;
	}

	/**
	 * One attempt across a link. Given up on after a timeout, the request travels in a
	 * routine of its own while the caller waits for its answer or the timeout, whichever
	 * comes first; without a timeout, the caller's own routine carries it there and back.
	 * @param request the request
	 * @param call the link, the callee's handler and the caller's timeout
	 * @param done what to do with how the attempt ended
	 */
	public void once(Request request, Call call, Consumer<Outcome> done) {
		Simulation simulation = this.context.simulation();
		EdgeRuntime edge = call.edge();
		edge.attempts().increment(simulation.now());
		Double timeoutMs = call.timeoutMs();
		if (timeoutMs == null) {
			new Hop(edge, request).travel(call.handler(), (outcome) -> {
				if (!outcome.ok()) {
					edge.failures().increment(simulation.now());
				}
				done.accept(outcome);
			});
			return;
		}
		Promise<Outcome> answered = simulation.promise();
		simulation.start((finish) -> new Hop(edge, request).travel(call.handler(), (outcome) -> {
			answered.succeed(outcome);
			finish.run();
		}));
		Timed timed = new Timed(call, timeoutMs, answered);
		simulation.anyOf(List.of(answered, simulation.timeout(timeoutMs)))
			.then(() -> afterWaiting(request, timed, done));
	}

	/**
	 * A call across a link to the part at its end, honouring the link's timeout and
	 * retrying a failure as often as the link allows, the backoff doubling each time.
	 * @param edge the link
	 * @param request the request
	 * @param callerId the calling part
	 * @param done what to do with the last attempt's outcome
	 */
	public void call(EdgeRuntime edge, Request request, String callerId, Consumer<Outcome> done) {
		Handler callee = this.context.component(edge.targetId())::handle;
		attempt(new Call(edge, callee, edge.config().timeoutMs(), callerId, FailureReason.DEPENDENCY_TIMEOUT), request,
				0, done);
	}

	/**
	 * A service's calls for one request, one after another in the links' order: the first
	 * failure fails the request, unless its link carries on after failures. Cache-aside
	 * (a read sent on to the links set to read on a cache miss) comes with the cache's
	 * slice of the port: until then the simulator refuses a cache, a design with such a
	 * link and no cache is not valid, and a read that meets one is refused.
	 * @param dependencies the links the request takes, in order
	 * @param request the request
	 * @param callerId the calling part
	 * @param done what to do with the outcome: the first failure that stopped the calls,
	 * or success
	 * @throws IllegalStateException for a read across a link set to read on a cache miss
	 */
	public void dependencies(List<EdgeRuntime> dependencies, Request request, String callerId, Consumer<Outcome> done) {
		if (request.operation() == RequestType.READ
				&& dependencies.stream().anyMatch((edge) -> edge.config().readsOnMiss())) {
			throw new IllegalStateException("cache-aside reads come with the cache's slice of the port");
		}
		new InOrder(dependencies, request, callerId, done).from(0);
	}

	private void attempt(Call call, Request request, int made, Consumer<Outcome> done) {
		once(request, call, (outcome) -> {
			EdgeRuntime edge = call.edge();
			if (outcome.ok() || made + 1 >= edge.config().maxAttempts()) {
				done.accept(outcome);
				return;
			}
			edge.retries().increment(this.context.simulation().now());
			this.context.simulation().after(edge.backoffMs(made + 1), () -> attempt(call, request, made + 1, done));
		});
	}

	private void afterWaiting(Request request, Timed timed, Consumer<Outcome> done) {
		Call call = timed.call();
		EdgeRuntime edge = call.edge();
		double now = this.context.simulation().now();
		if (timed.answered().triggered()) {
			Outcome outcome = timed.answered().value();
			if (!outcome.ok()) {
				edge.failures().increment(now);
			}
			done.accept(outcome);
			return;
		}
		edge.timeouts().increment(now);
		if (request.traced()) {
			request.span(new Span(call.callerId(), edge.id(), SpanKind.TIMEOUT, now, now, request.depth(),
					"gave up after " + PythonFormat.format(timed.timeoutMs(), "g") + " ms"));
		}
		done.accept(Outcome.timedOut(call.timeoutReason(), call.callerId(), edge.id()));
	}

	private double latency(EdgeRuntime edge) {
		double latency = edge.sampleLatency();
		return this.context.fault(FaultKind.LATENCY, edge.id())
			.map((fault) -> latency + fault.extraMs())
			.orElse(latency);
	}

	/**
	 * A call across a link.
	 *
	 * @param edge the link
	 * @param handler the callee's handler
	 * @param timeoutMs how long the caller waits for the answer; {@code null} for as long
	 * as it takes
	 * @param callerId the caller
	 * @param timeoutReason the failure the caller names when it gives up
	 */
	public record Call(EdgeRuntime edge, Handler handler, @Nullable Double timeoutMs, String callerId,
			FailureReason timeoutReason) {

	}

	/**
	 * An attempt the caller gives up on after a timeout, and the answer it waits for.
	 *
	 * @param call the attempt
	 * @param timeoutMs how long the caller waits
	 * @param answered the callee's answer, once it comes
	 */
	private record Timed(Call call, double timeoutMs, Promise<Outcome> answered) {
	}

	/**
	 * One request's trip across a link and back: the latency out, the callee (or an
	 * outage that refuses it), the latency back, then the callee's residence time and,
	 * for a traced request, the steps.
	 */
	private final class Hop {

		private final EdgeRuntime edge;

		private final Request request;

		private final int depth;

		private final double sent;

		private double arrived;

		private double answered;

		private boolean down;

		Hop(EdgeRuntime edge, Request request) {
			this.edge = edge;
			this.request = request;
			this.depth = request.depth();
			this.sent = Calls.this.context.simulation().now();
		}

		void travel(Handler handler, Consumer<Outcome> back) {
			Calls.this.context.enterTransit();
			Calls.this.context.simulation().after(latency(this.edge), () -> arrive(handler, back));
		}

		private void arrive(Handler handler, Consumer<Outcome> back) {
			@Nullable Outcome refused = outage();
			this.arrived = Calls.this.context.simulation().now();
			if (refused != null) {
				this.down = true;
				answer(refused, back);
				return;
			}
			this.request.moveTo(this.depth + 1);
			handler.handle(this.request, (outcome) -> {
				this.request.moveTo(this.depth);
				answer(outcome, back);
			});
		}

		private void answer(Outcome outcome, Consumer<Outcome> back) {
			this.answered = Calls.this.context.simulation().now();
			Calls.this.context.simulation().after(latency(this.edge), () -> {
				record(outcome);
				Calls.this.context.leaveTransit();
				back.accept(outcome);
			});
		}

		/**
		 * The callee refusing the request because it is down, counted at the callee.
		 * @return the refusal, or {@code null} when the callee is up
		 */
		private @Nullable Outcome outage() {
			String target = this.edge.targetId();
			if (Calls.this.context.fault(FaultKind.OUTAGE, target).isEmpty()) {
				return null;
			}
			double now = Calls.this.context.simulation().now();
			NodeMetrics callee = Calls.this.context.component(target).metrics();
			callee.arrive(now);
			callee.reject(FailureReason.OUTAGE, now);
			return Outcome.rejected(FailureReason.OUTAGE, target);
		}

		private void record(Outcome outcome) {
			if (!this.down) {
				Calls.this.context.component(this.edge.targetId())
					.metrics()
					.residence()
					.record(this.answered, this.answered - this.arrived);
			}
			if (this.request.traced()) {
				recordSpans(outcome);
			}
		}

		private void recordSpans(Outcome outcome) {
			double now = Calls.this.context.simulation().now();
			if (this.arrived > this.sent) {
				this.request
					.span(new Span(null, this.edge.id(), SpanKind.NETWORK, this.sent, this.arrived, this.depth, null));
			}
			String target = this.edge.targetId();
			if (this.down) {
				this.request.span(new Span(target, null, SpanKind.REJECTED, this.arrived, this.arrived, this.depth + 1,
						FailureReason.OUTAGE.json()));
			}
			else {
				this.request.span(new Span(target, null, SpanKind.HANDLE, this.arrived, this.answered, this.depth + 1,
						detail(outcome)));
			}
			if (now > this.answered) {
				this.request
					.span(new Span(null, this.edge.id(), SpanKind.NETWORK, this.answered, now, this.depth, null));
			}
		}

		private static @Nullable String detail(Outcome outcome) {
			if (outcome.ok()) {
				return null;
			}
			FailureReason reason = outcome.reason();
			return (reason != null) ? reason.json() : outcome.status().json();
		}

	}

	/**
	 * A service's calls for one request, made one after another.
	 */
	private final class InOrder {

		private final List<EdgeRuntime> links;

		private final Request request;

		private final String callerId;

		private final Consumer<Outcome> done;

		InOrder(List<EdgeRuntime> links, Request request, String callerId, Consumer<Outcome> done) {
			this.links = links;
			this.request = request;
			this.callerId = callerId;
			this.done = done;
		}

		void from(int index) {
			if (index == this.links.size()) {
				this.done.accept(Outcome.OK);
				return;
			}
			EdgeRuntime edge = this.links.get(index);
			call(edge, this.request, this.callerId, (outcome) -> {
				if (!outcome.ok() && !edge.config().continueOnFailure()) {
					this.done.accept(outcome);
					return;
				}
				from(index + 1);
			});
		}

	}

}
