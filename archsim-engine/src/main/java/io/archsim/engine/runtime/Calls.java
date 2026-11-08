package io.archsim.engine.runtime;

import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Promise;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.report.FailureReason;
import io.archsim.model.document.FaultKind;
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
	 * One attempt across a link, given up on after a timeout: the request travels in a
	 * routine of its own while the caller waits for its answer or the timeout, whichever
	 * comes first.
	 * @param request the request
	 * @param call the link, the callee's handler and the caller's timeout
	 * @param done what to do with how the attempt ended
	 */
	public void once(Request request, Call call, Consumer<Outcome> done) {
		Simulation simulation = this.context.simulation();
		call.edge().attempts().increment(simulation.now());
		Promise<Outcome> answered = simulation.promise();
		simulation.start((finish) -> new Hop(call.edge(), request).travel(call.handler(), (outcome) -> {
			answered.succeed(outcome);
			finish.run();
		}));
		simulation.anyOf(List.of(answered, simulation.timeout(call.timeoutMs())))
			.then(() -> afterWaiting(request, call, answered, done));
	}

	private void afterWaiting(Request request, Call call, Promise<Outcome> answered, Consumer<Outcome> done) {
		EdgeRuntime edge = call.edge();
		double now = this.context.simulation().now();
		if (answered.triggered()) {
			Outcome outcome = answered.value();
			if (!outcome.ok()) {
				edge.failures().increment(now);
			}
			done.accept(outcome);
			return;
		}
		edge.timeouts().increment(now);
		if (request.traced()) {
			request.span(new Span(call.callerId(), edge.id(), SpanKind.TIMEOUT, now, now, request.depth(),
					"gave up after " + PythonFormat.format(call.timeoutMs(), "g") + " ms"));
		}
		done.accept(Outcome.timedOut(call.timeoutReason(), call.callerId(), edge.id()));
	}

	private double latency(EdgeRuntime edge) {
		double latency = edge.sampleLatency();
		return this.context.fault(FaultKind.LATENCY, edge.id())
			.map((fault) -> latency + fault.extraMs())
			.orElse(latency);
	}

	private void after(double delay, Runnable next) {
		if (delay > 0) {
			this.context.simulation().timeout(delay).then(next);
		}
		else {
			next.run();
		}
	}

	/**
	 * A call across a link.
	 *
	 * @param edge the link
	 * @param handler the callee's handler
	 * @param timeoutMs how long the caller waits for the answer
	 * @param callerId the caller
	 * @param timeoutReason the failure the caller names when it gives up
	 */
	public record Call(EdgeRuntime edge, Handler handler, double timeoutMs, String callerId,
			FailureReason timeoutReason) {

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
			after(latency(this.edge), () -> arrive(handler, back));
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
			after(latency(this.edge), () -> {
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

}
