package io.archsim.engine.components.cache;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.WorkerPool;

/**
 * A cache's commands: each takes an executor on its shard (or is refused by the shard's
 * queue), waits out a snapshot's fork, then runs its time on the shard's CPU.
 */
final class CacheCommands {

	private final Simulation simulation;

	private final String nodeId;

	private final NodeMetrics metrics;

	private final DoubleSupplier commandTime;

	/**
	 * A cache's commands.
	 * @param simulation the run
	 * @param nodeId the cache
	 * @param metrics its metrics, which record each command's time and holding time
	 * @param commandTime draws a command's time, in milliseconds
	 */
	CacheCommands(Simulation simulation, String nodeId, NodeMetrics metrics, DoubleSupplier commandTime) {
		this.simulation = simulation;
		this.nodeId = nodeId;
		this.metrics = metrics;
		this.commandTime = commandTime;
	}

	/**
	 * Executes one command on an executor of a shard.
	 * @param shard the shard
	 * @param request the request the command is for
	 * @param ran what to do once it ran
	 * @param refused what to do with why the shard's queue refused it
	 */
	void run(CacheShard shard, Request request, Runnable ran, Consumer<FailureReason> refused) {
		shard.pool().acquire(request, this.nodeId, (acquired) -> {
			switch (acquired) {
				case WorkerPool.Taken taken -> {
					Command command = new Command(shard, taken.slot(), this.simulation.now());
					waitOutFork(request, shard, () -> execute(command, ran));
				}
				case WorkerPool.Refused refusal -> refused.accept(refusal.reason());
			}
		});
	}

	private void waitOutFork(Request request, CacheShard shard, Runnable next) {
		double start = this.simulation.now();
		if (shard.pausedUntil() <= start) {
			next.run();
			return;
		}
		this.simulation.after(shard.pausedUntil() - start, () -> {
			request.span(this.nodeId, SpanKind.FORK, new Request.Interval(start, this.simulation.now()),
					"snapshot fork blocked the command thread");
			next.run();
		});
	}

	private void execute(Command command, Runnable ran) {
		double workMs = this.commandTime.getAsDouble();
		CacheShard shard = command.shard();
		shard.cores().run(workMs, () -> {
			shard.pool().release(command.slot());
			double now = this.simulation.now();
			this.metrics.service().record(now, workMs);
			this.metrics.hold().record(now, now - command.start());
			ran.run();
		});
	}

	/**
	 * A command holding an executor.
	 *
	 * @param shard the shard it runs on
	 * @param slot its executor
	 * @param start when it took the executor
	 */
	private record Command(CacheShard shard, Resource.Request slot, double start) {
	}

}
