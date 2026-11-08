package io.archsim.engine.components.database;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.Replication;
import io.archsim.model.document.NodeSpec;

/**
 * A database during a run: shards of a primary and its read replicas, a request routed to
 * its key's shard or, for a scattered read, to every shard ({@link DatabaseRequests}),
 * each operation on a connection, with tickets, row locks, its time and disk reads, and a
 * write's log flush ({@link Operation}), then its shard's replicas replaying it
 * ({@link ReplicationPipeline}). Its report adds its CPU's, buffer cache's, waits' and
 * replicas' figures and series ({@link DatabaseReport}) and formulas
 * ({@link DatabaseExplain}).
 */
public final class DatabaseRuntime extends RuntimeComponent {

	private final DatabaseState state;

	private final DatabaseRequests requests;

	/**
	 * A database at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 */
	public DatabaseRuntime(SimContext context, NodeSpec node, DatabaseConfig config) {
		super(context, node,
				OptionalInt.of(config.partitions() * (1 + config.readReplicas()) * config.maxConnections()));
		this.state = DatabaseState.of(context, config, new DatabaseState.Owner(node.id(), metrics(), this::sampleTime));
		this.requests = new DatabaseRequests(this.state, context.stream("node:" + node.id() + ":scatter"));
		ReplicationPipeline replication = this.state.replication();
		if (replication != null) {
			replication.start();
		}
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		this.requests.handle(request, done);
	}

	/**
	 * Whether the database's behaviour depends on the requests' keys: shards, row locks,
	 * reads that wait for writers, or replicas that replay writes.
	 */
	@Override
	public boolean usesKeys() {
		DatabaseSettings settings = this.state.settings();
		return settings.partitions() > 1 || settings.locksRows() || settings.readersWaitForWriters()
				|| settings.replication() != Replication.NONE;
	}

	/**
	 * Whether a replica still has writes to replay.
	 */
	@Override
	public boolean backgroundBusy() {
		ReplicationPipeline replication = this.state.replication();
		return replication != null && replication.behind();
	}

	@Override
	public void finish(double end) {
		super.finish(end);
		this.state.shards().finish(end);
	}

	@Override
	public SequencedMap<String, Double> resourceCapacity(OptionalDouble holdMs, OptionalDouble serviceMs) {
		return DatabaseReport.resourceCapacity(this.state, serviceMs);
	}

	@Override
	public SequencedMap<String, @Nullable Number> extraSummary() {
		return DatabaseReport.extraSummary(this.state);
	}

	@Override
	public SequencedMap<String, List<@Nullable Double>> extraTimeseries() {
		return DatabaseReport.extraTimeseries(this.state);
	}

	@Override
	public List<Formula> explain(NodeSummary summary) {
		return DatabaseExplain.of(this.state, context().workload(), context().timeline().windowMs(), summary);
	}

	@Override
	public List<TechnologyTrait> behaviour() {
		return DatabaseReport.behaviour(this.state.settings());
	}

}
