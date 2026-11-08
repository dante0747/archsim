/**
 * A message queue during a run: publishes acknowledged after their publish time (and
 * blocked by a memory alarm), a backlog held in partitions or one shared queue, consumers
 * that take messages one at a time or poll in cycles, deliveries retried, dead-lettered
 * or redelivered after a visibility timeout, and what it adds to a report.
 */
@NullMarked
package io.archsim.engine.components.queue;

import org.jspecify.annotations.NullMarked;
