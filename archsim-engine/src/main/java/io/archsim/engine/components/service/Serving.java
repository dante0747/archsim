package io.archsim.engine.components.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.Request.Interval;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.model.component.service.Warmup;
import io.archsim.model.document.RequestType;

/**
 * A request's way through a service's replica: refused when the replica's memory is full,
 * then a handler (or a refusal from the queue), the service time (its CPU share on the
 * replica's cores, the rest waiting on the handler), and the calls to the dependencies in
 * order, the handler held through them when the handlers block.
 */
final class Serving {

	private final SimContext context;

	private final ServiceState state;

	private final GarbageCollector collector;

	private final DoubleSupplier serviceTime;

	private final Map<RequestType, List<EdgeRuntime>> dependencies = new EnumMap<>(RequestType.class);

	/**
	 * A service's handling of requests.
	 * @param context the run
	 * @param state the service's state
	 * @param collector its garbage collector
	 * @param serviceTime a sampled service time, stretched by a slowdown under way
	 */
	Serving(SimContext context, ServiceState state, GarbageCollector collector, DoubleSupplier serviceTime) {
		this.context = context;
		this.state = state;
		this.collector = collector;
		this.serviceTime = serviceTime;
	}

	/**
	 * Serves a request on a replica.
	 * @param request the request
	 * @param replica the replica
	 * @param done what to do with the outcome
	 */
	void serve(Request request, Replica replica, Consumer<Outcome> done) {
		double arrived = now();
		this.state.metrics().arrive(arrived);
		RateMeter meter = replica.meter();
		if (meter != null) {
			meter.arrive(arrived);
		}
		OptionalInt memorySlots = this.state.memorySlots();
		if (memorySlots.isPresent() && replica.load() >= memorySlots.getAsInt()) {
			refuse(request, FailureReason.OUT_OF_MEMORY, "out of memory", done);
			return;
		}
		replica.hold();
		this.state.notePeakLoad(replica.load());
		replica.pool().acquire(request, this.state.nodeId(), (acquired) -> handle(request, replica, acquired, done));
	}

	// served with the handler taken, or refused by the queue
	private void handle(Request request, Replica replica, WorkerPool.Acquired acquired, Consumer<Outcome> done) {
		switch (acquired) {
			case WorkerPool.Taken taken -> {
				Held held = new Held(replica, taken.slot(), now());
				work(request, held, () -> callDependencies(request, held, done));
			}
			case WorkerPool.Refused refused -> {
				replica.letGo();
				refuse(request, refused.reason(), refused.reason().json(), done);
			}
		}
	}

	private void refuse(Request request, FailureReason reason, String detail, Consumer<Outcome> done) {
		double now = now();
		this.state.metrics().reject(reason, now);
		request.span(this.state.nodeId(), SpanKind.REJECTED, new Interval(now, now), detail);
		done.accept(Outcome.rejected(reason, this.state.nodeId()));
	}

	// the service time: its CPU share on the replica's cores, the rest waiting (I/O) on
	// the handler without a core
	private void work(Request request, Held held, Runnable then) {
		Replica replica = held.replica();
		double start = held.since();
		double warm = this.serviceTime.getAsDouble() * warmFactor(replica, start);
		RateMeter meter = replica.meter();
		double serviceMs = (meter != null) ? warm * loadFactor(meter, start) : warm;
		double cpuMs = serviceMs * this.state.config().cpuFraction();
		this.state.metrics().cpu().add(start, 1);
		this.collector.cpu(request, replica, cpuMs, () -> this.context.simulation().after(serviceMs - cpuMs, () -> {
			double now = now();
			this.state.metrics().cpu().add(now, -1);
			this.state.metrics().service().record(now, serviceMs);
			request.span(this.state.nodeId(), SpanKind.SERVICE, new Interval(start, now), null);
			this.collector.served(replica);
			then.run();
		}));
	}

	private void callDependencies(Request request, Held held, Consumer<Outcome> done) {
		List<EdgeRuntime> calls = dependenciesFor(request.operation());
		Consumer<Outcome> finish = (outcome) -> {
			held.replica().letGo();
			this.state.metrics().depart(now(), outcome, request);
			done.accept(outcome);
		};
		if (this.state.config().holdsHandlerThroughCalls()) {
			this.context.calls().dependencies(calls, request, this.state.nodeId(), (outcome) -> {
				release(held);
				finish.accept(outcome);
			});
		}
		else {
			release(held);
			this.context.calls().dependencies(calls, request, this.state.nodeId(), finish);
		}
	}

	private void release(Held held) {
		held.replica().pool().release(held.slot());
		double now = now();
		this.state.metrics().hold().record(now, now - held.since());
	}

	// a replica started during the run is slower while it warms up, less and less; one
	// present at the start is warm
	private double warmFactor(Replica replica, double now) {
		Warmup warmup = this.state.warmup();
		if (warmup == null || replica.born() <= 0) {
			return 1.0;
		}
		double left = 1.0 - (now - replica.born()) / warmup.durationMs();
		return (left > 0) ? 1.0 + (warmup.factor() - 1.0) * left : 1.0;
	}

	// the service-time factor at the replica's rate over the last second
	private double loadFactor(RateMeter meter, double now) {
		double factor = this.state.curve().factorAt(meter.rps(now));
		this.state.recordFactor(now, factor);
		return factor;
	}

	private List<EdgeRuntime> dependenciesFor(RequestType operation) {
		return this.dependencies.computeIfAbsent(operation, (type) -> this.context.outEdges(this.state.nodeId(), "out")
			.stream()
			.filter((edge) -> edge.carries(type))
			.sorted(Comparator.comparingInt((EdgeRuntime edge) -> edge.config().order()).thenComparing(EdgeRuntime::id))
			.toList());
	}

	private double now() {
		return this.context.simulation().now();
	}

	/**
	 * A handler a request holds on a replica, and since when.
	 *
	 * @param replica the replica
	 * @param slot the handler
	 * @param since when the request took it
	 */
	private record Held(Replica replica, Resource.Request slot, double since) {
	}

}
