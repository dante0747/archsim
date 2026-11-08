package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

import io.archsim.engine.kernel.Routine;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.KeyShards;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.model.document.RequestType;

/**
 * How a database takes a request: past its query router, then on the shard its key hashes
 * to, or, for a scattered read, on every shard at once, the slowest deciding when it is
 * done and the first shard's refusal failing it.
 */
final class DatabaseRequests {

	private final DatabaseState state;

	private final PythonRandom scatterStream;

	private final double scatterShare;

	private final double routerMs;

	/**
	 * A database's way with requests.
	 * @param state the database
	 * @param scatterStream the stream that draws which reads are scattered
	 */
	DatabaseRequests(DatabaseState state, PythonRandom scatterStream) {
		this.state = state;
		this.scatterStream = scatterStream;
		this.scatterShare = state.settings().scatterShare();
		this.routerMs = state.settings().routerOverheadMs();
	}

	/**
	 * Takes a request.
	 * @param request the request
	 * @param done what to do with how it ended
	 */
	void handle(Request request, Consumer<Outcome> done) {
		this.state.metrics().arrive(now());
		// a query router in front of shards (a scattered read needs shards too)
		if (this.routerMs > 0 && this.state.settings().partitions() > 1) {
			this.state.simulation().after(this.routerMs, () -> route(request, done));
			return;
		}
		route(request, done);
	}

	private void route(Request request, Consumer<Outcome> done) {
		if (scatters(request)) {
			scatter(request, done);
			return;
		}
		int partitions = this.state.settings().partitions();
		int shard = (partitions > 1) ? KeyShards.of(request.arrival().key(), partitions) : 0;
		Operation.counted(this.state, request, shard)
			.run(() -> served(request, done), (reason) -> refuse(request, reason, done));
	}

	// draws whether a read goes to every shard
	private boolean scatters(Request request) {
		return request.operation() == RequestType.READ && this.scatterShare > 0
				&& (this.scatterShare >= 1.0 || this.scatterStream.nextDouble() < this.scatterShare);
	}

	private void scatter(Request request, Consumer<Outcome> done) {
		double start = now();
		this.state.metrics().count(DatabaseCounters.SCATTER_READS, start);
		int partitions = this.state.settings().partitions();
		SortedMap<Integer, FailureReason> refusals = new TreeMap<>();
		List<Routine> operations = new ArrayList<>(partitions);
		for (int shard = 0; shard < partitions; shard++) {
			int target = shard;
			Operation operation = (shard == 0) ? Operation.counted(this.state, request, shard)
					: Operation.uncounted(this.state, request, shard);
			operations.add(this.state.simulation().start((finish) -> operation.run(finish, (reason) -> {
				refusals.put(target, reason);
				finish.run();
			})));
		}
		this.state.simulation().allOf(operations).then(() -> {
			request.span(this.state.nodeId(), SpanKind.SCATTER, new Request.Interval(start, now()),
					partitions + " shards, slowest decides");
			if (refusals.isEmpty()) {
				served(request, done);
			}
			else {
				refuse(request, refusals.values().iterator().next(), done);
			}
		});
	}

	private void served(Request request, Consumer<Outcome> done) {
		double now = now();
		this.state.metrics()
			.count((request.operation() == RequestType.READ) ? DatabaseCounters.READS : DatabaseCounters.WRITES, now);
		this.state.metrics().depart(now, Outcome.OK, request);
		done.accept(Outcome.OK);
	}

	private void refuse(Request request, FailureReason reason, Consumer<Outcome> done) {
		double now = now();
		this.state.metrics().reject(reason, now);
		request.span(this.state.nodeId(), SpanKind.REJECTED, new Request.Interval(now, now), reason.json());
		done.accept(Outcome.rejected(reason, this.state.nodeId()));
	}

	private double now() {
		return this.state.simulation().now();
	}

}
