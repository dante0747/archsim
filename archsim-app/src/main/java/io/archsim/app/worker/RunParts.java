package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * What every run a worker simulates shares.
 *
 * @param simulator simulates a run
 * @param writer writes a run's progress and how it ended, or gives it back
 * @param outcomes shapes how a run ended
 * @param context the worker: its name, its limits on a run and its cancel grace
 * @param deadlineMargin added to a run's wall-clock limit for its hard deadline: start-up
 * and the report
 * @param nanos the time, for the pace of progress writes
 * @param runs the platform threads runs simulate on
 * @param timers the thread the deadlines and graces fire on
 */
record RunParts(RunSimulator simulator, RunWriter writer, RunOutcomes outcomes, WorkerContext context,
		Duration deadlineMargin, LongSupplier nanos, Executor runs, ScheduledExecutorService timers) {

}
