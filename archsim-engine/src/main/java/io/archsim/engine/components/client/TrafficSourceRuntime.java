package io.archsim.engine.components.client;

import java.util.OptionalInt;
import java.util.function.Consumer;

import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Calls.Call;
import io.archsim.engine.runtime.Calls.Errand;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.Handler;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.TracedRequest;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.document.NodeSpec;

/**
 * A client group during a run: it sends each user request it is given across its one
 * link, gives up after its timeout, retries with exponential backoff, and records what
 * its users experienced.
 */
public final class TrafficSourceRuntime extends RuntimeComponent {

	private static final String OUT = "out";

	private final ClientConfig config;

	private final ClientMetrics clients;

	private final ClientGroup group;

	/**
	 * A client group at the start of a run.
	 * @param context the run
	 * @param node the client group
	 * @param config its settings
	 * @param clients the run's end-to-end client metrics, shared by every client group
	 */
	public TrafficSourceRuntime(SimContext context, NodeSpec node, ClientConfig config, ClientMetrics clients) {
		super(context, node, OptionalInt.empty());
		this.config = config;
		this.clients = clients;
		this.group = new ClientGroup(context.timeline());
	}

	/**
	 * Refuses the call: nothing calls a client group.
	 * @throws IllegalStateException always
	 */
	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		throw new IllegalStateException("a traffic source cannot be the target of a call");
	}

	/**
	 * Sends a user request and sees it through: attempts, timeouts, retries.
	 * @param request the request, as it arrived
	 * @param finish what to run once the client is done with it
	 */
	public void submit(Request request, Runnable finish) {
		double now = now();
		metrics().arrive(now);
		this.clients.offered().increment(now);
		this.clients.cohort(request).arrive();
		this.group.offered().increment(now);
		if (request.arrival().measured()) {
			this.group.measured().arrive();
		}
		EdgeRuntime edge = outEdges(OUT).getFirst();
		Handler handler = context().component(edge.targetId())::handle;
		new Submission(request,
				new Call(edge, handler, this.config.timeoutMs(), id(), FailureReason.CLIENT_TIMEOUT, Errand.WORK),
				finish)
			.attempt(0);
	}

	public ClientConfig config() {
		return this.config;
	}

	public ClientGroup group() {
		return this.group;
	}

	/**
	 * One user request on its way: each attempt a request of its own, so work its client
	 * gave up on can be told apart.
	 */
	private final class Submission {

		private final Request request;

		private final Call call;

		private final Runnable finish;

		Submission(Request request, Call call, Runnable finish) {
			this.request = request;
			this.call = call;
			this.finish = finish;
		}

		void attempt(int attempt) {
			double now = now();
			TrafficSourceRuntime.this.clients.attempts().increment(now);
			TrafficSourceRuntime.this.group.attempts().increment(now);
			Request sent = this.request.attempt();
			context().calls().once(sent, this.call, (outcome) -> afterAttempt(attempt, sent, outcome));
		}

		private void afterAttempt(int attempt, Request sent, Outcome outcome) {
			if (outcome.failedFor(FailureReason.CLIENT_TIMEOUT)) {
				sent.abandon();
			}
			if (outcome.ok() || attempt + 1 >= TrafficSourceRuntime.this.config.maxAttempts()) {
				finish(outcome, attempt + 1);
				return;
			}
			this.call.edge().retries().increment(now());
			double backoff = TrafficSourceRuntime.this.config.retryBackoffMs() * (1L << attempt);
			if (backoff <= 0) {
				attempt(attempt + 1);
				return;
			}
			double waited = now();
			simulation().timeout(backoff).then(() -> {
				sent.span(id(), SpanKind.BACKOFF, new Request.Interval(waited, now()),
						"retry " + (attempt + 2) + " after backoff");
				attempt(attempt + 1);
			});
		}

		private void finish(Outcome outcome, int attempts) {
			double now = now();
			TrafficSourceRuntime.this.clients.record(this.request, outcome, now,
					TrafficSourceRuntime.this.config.timeoutMs());
			TrafficSourceRuntime.this.group.record(this.request, outcome, now);
			metrics().depart(now, outcome, null);
			if (this.request.traced()) {
				context().trace(new TracedRequest(id(), this.request, now, outcome, attempts));
			}
			this.finish.run();
		}

	}

}
