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
 * write's log flush ({@link Operation}). Its report adds its CPU's, buffer cache's and
 * waits' figures and series ({@link DatabaseReport}) and formulas
 * ({@link DatabaseExplain}). Replicas that replay the primary's writes and capacity
 * metered in units come with the next part of the port.
 */
public final class DatabaseRuntime extends RuntimeComponent {

	private final DatabaseState state;

	private final DatabaseRequests requests;

	/**
	 * A database at the start of a run.
	 * @param context the run
	 * @param node the part
	 * @param config its settings
	 * @throws UnsupportedOperationException for a database whose replicas replay its
	 * writes, or one metered in capacity units, which the Java simulator does not
	 * simulate yet
	 */
	public DatabaseRuntime(SimContext context, NodeSpec node, DatabaseConfig config) {
		super(context, node,
				OptionalInt.of(config.partitions() * (1 + config.readReplicas()) * config.maxConnections()));
		refuseWhatComesLater(config);
		this.state = DatabaseState.of(context, config, new DatabaseState.Owner(node.id(), metrics(), this::sampleTime));
		this.requests = new DatabaseRequests(this.state, context.stream("node:" + node.id() + ":scatter"));
	}

	// replicas that replay writes and capacity units come with the next part of the port
	private static void refuseWhatComesLater(DatabaseConfig config) {
		if (new DatabaseSettings(config).replication() != Replication.NONE) {
			throw new UnsupportedOperationException(
					"the Java simulator does not simulate a database that replicates to its read replicas yet");
		}
		if (config.metered()) {
			throw new UnsupportedOperationException(
					"the Java simulator does not simulate a database metered in capacity units yet");
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
