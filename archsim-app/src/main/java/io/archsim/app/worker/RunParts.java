package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * What every run a worker simulates shares.
 *
 * @param simulator simulates a run
 * @param leases the runs' leases, where their progress is written
 * @param writer writes how a run ended, or gives it back
 * @param outcomes shapes how a run ended
 * @param workerId the worker
 * @param limits the worker's limits on a run, which the run's own tighten
 * @param deadlineMargin added to a run's wall-clock limit for its hard deadline: start-up
 * and the report
 * @param cancelGrace how long a cancelled run may take to stop before it is written
 * cancelled
 * @param nanos the time, for the pace of progress writes
 * @param runs the platform threads runs simulate on
 * @param timers the thread the deadlines and graces fire on
 */
record RunParts(RunSimulator simulator, LeaseRepository leases, OutcomeWriter writer, RunOutcomes outcomes,
		String workerId, SimulationLimits limits, Duration deadlineMargin, Duration cancelGrace, LongSupplier nanos,
		Executor runs, ScheduledExecutorService timers) {

}
