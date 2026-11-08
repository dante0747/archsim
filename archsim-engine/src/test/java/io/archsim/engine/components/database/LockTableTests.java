package io.archsim.engine.components.database;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;

import static org.assertj.core.api.Assertions.assertThat;

/** A shard's row locks: made on demand, dropped once nothing holds or waits for them. */
class LockTableTests {

	private final LockTable locks = new LockTable(new Simulation());

	@Test
	void held_aKeyNoWriteLocked_isEmpty() {
		assertThat(this.locks.held(7)).isEmpty();
	}

	@Test
	void held_aKeyAWriteHolds_isItsLock() {
		LockTable.Held write = this.locks.acquire(7);

		assertThat(this.locks.held(7)).contains(write.lock());
	}

	@Test
	void release_theOnlyWrite_dropsTheLock() {
		LockTable.Held write = this.locks.acquire(7);

		this.locks.release(write);

		assertThat(this.locks.held(7)).isEmpty();
	}

	@Test
	void release_aWriteAnotherWaitsBehind_keepsTheLock() {
		LockTable.Held first = this.locks.acquire(7);
		LockTable.Held second = this.locks.acquire(7);

		this.locks.release(first);

		assertThat(this.locks.held(7)).contains(second.lock());
	}

}
