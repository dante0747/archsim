package io.archsim.engine.components.queue;

import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;

/**
 * How a queue's consumers process what they take.
 *
 * @param simulation the run
 * @param deliveries what a consumer does with each message
 * @param concurrency the messages one consumer processes at once
 * @param polls whether consumers poll in cycles rather than taking each message as soon
 * as they are free
 * @param fetchOverheadMs draws a poll's fetch-and-commit time; {@code null} without one
 */
record Consuming(Simulation simulation, Deliveries deliveries, int concurrency, boolean polls,
		@Nullable DoubleSupplier fetchOverheadMs) {
}
