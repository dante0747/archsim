package io.archsim.engine.components.database;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.Timing;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.Replication;

/**
 * What a database's operations share during a run.
 *
 * @param simulation the run
 * @param nodeId the database
 * @param metrics its metrics
 * @param settings its settings in effect
 * @param shards its shards' instances
 * @param controls its shards' locks, logs and throughput limits
 * @param draws what its operations draw
 * @param replication its replicas' replay of its writes; {@code null} without replication
 */
record DatabaseState(Simulation simulation, String nodeId, NodeMetrics metrics, DatabaseSettings settings,
		DatabaseShards shards, ShardControls controls, OperationDraws draws,
		@Nullable ReplicationPipeline replication) {

	/**
	 * A database at the start of a run.
	 * @param context the run
	 * @param config the database's settings
	 * @param owner the part it is
	 * @return its state
	 */
	static DatabaseState of(SimContext context, DatabaseConfig config, Owner owner) {
		DatabaseSettings settings = new DatabaseSettings(config);
		String prefix = "node:" + owner.nodeId() + ":";
		PythonRandom service = context.stream(prefix + "service");
		PythonRandom flush = context.stream(prefix + "flush");
		Sampler read = Sampler.of(config.readServiceTimeMs());
		Sampler write = Sampler.of(config.writeServiceTimeMs());
		Sampler flushTime = Sampler.of(config.commitFlushMs());
		Timing timing = owner.timing();
		OperationDraws.ServiceTimes times = new OperationDraws.ServiceTimes(() -> timing.sample(read, service),
				() -> timing.sample(write, service));
		DatabaseShards shards = new DatabaseShards(context, settings, owner.metrics());
		ReplicationPipeline replication = (settings.replication() != Replication.NONE)
				? new ReplicationPipeline(context, settings, shards,
						new ReplicationPipeline.Accounts(owner.metrics(), context.stream(prefix + "replication")))
				: null;
		return new DatabaseState(context.simulation(), owner.nodeId(), owner.metrics(), settings, shards,
				new ShardControls(context.simulation(), settings,
						new ShardControls.Accounts(owner.metrics(), () -> timing.sample(flushTime, flush))),
				new OperationDraws(settings, times, context.stream(prefix + "buffer")), replication);
	}

	/**
	 * The part a database's state belongs to.
	 *
	 * @param nodeId its id
	 * @param metrics its metrics
	 * @param timing how it draws its times
	 */
	record Owner(String nodeId, NodeMetrics metrics, Timing timing) {
	}

}
