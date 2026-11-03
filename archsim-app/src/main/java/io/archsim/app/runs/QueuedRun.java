package io.archsim.app.runs;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A run being written to the queue.
 *
 * @param id its id
 * @param run what it simulates, where and how
 * @param createdBy who starts it
 * @param group its seed group and place there, if any
 * @param queuedAt when
 */
record QueuedRun(String id, NewRun run, String createdBy, @Nullable GroupPlace group, Instant queuedAt) {

}
