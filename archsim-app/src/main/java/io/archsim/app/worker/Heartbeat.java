package io.archsim.app.worker;

/**
 * What a worker says each time it beats: who it is, how many runs it takes at once and
 * how many it has.
 *
 * @param worker the worker
 * @param slots the runs it takes at once
 * @param running the runs it has
 */
record Heartbeat(WorkerIdentity worker, int slots, int running) {

}
