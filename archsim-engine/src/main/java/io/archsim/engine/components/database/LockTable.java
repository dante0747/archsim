package io.archsim.engine.components.database;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;

/**
 * A shard's row locks: an exclusive lock per key, made when a write first asks for it and
 * dropped once nothing holds it or waits for it, so the table stays as small as the keys
 * in use.
 */
final class LockTable {

	private final Simulation simulation;

	private final Map<Integer, Resource> locks = new HashMap<>();

	/**
	 * A shard's locks, none held.
	 * @param simulation the run
	 */
	LockTable(Simulation simulation) {
		this.simulation = simulation;
	}

	/**
	 * Asks for a key's lock.
	 * @param key the key
	 * @return the lock and the request for it, granted when the write holds the lock
	 */
	Held acquire(int key) {
		Resource lock = this.locks.computeIfAbsent(key, (ignored) -> new Resource(this.simulation, 1));
		return new Held(key, lock, lock.request());
	}

	/**
	 * The lock on a key while a write holds it or waits for it.
	 * @param key the key
	 * @return the lock; empty when no write holds it or waits for it
	 */
	Optional<Resource> held(int key) {
		Resource lock = this.locks.get(key);
		return (lock != null && (lock.count() > 0 || lock.queued() > 0)) ? Optional.of(lock) : Optional.empty();
	}

	/**
	 * Gives a write's lock back.
	 * @param held the lock the write holds
	 */
	void release(Held held) {
		held.lock().release(held.request());
		forgetIfFree(held.key(), held.lock());
	}

	/**
	 * Drops a key's lock when nothing holds it or waits for it.
	 * @param key the key
	 * @param lock its lock
	 */
	void forgetIfFree(int key, Resource lock) {
		if (lock.count() == 0 && lock.queued() == 0) {
			this.locks.remove(key);
		}
	}

	/**
	 * A write's request for a key's lock.
	 *
	 * @param key the key
	 * @param lock its lock
	 * @param request the request, granted when the write holds the lock
	 */
	record Held(int key, Resource lock, Resource.Request request) {
	}

}
