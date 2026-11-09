package io.archsim.app.worker;

import org.junit.jupiter.api.Test;

import io.archsim.app.store.Text;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a worker is: its host, its process and a random suffix, as the Python worker names
 * itself, within the 64 characters a lease and {@code job_workers} hold.
 */
class WorkerIdentityTests {

	@Test
	void ofThisProcess_always_namesTheHostAndTheProcessWithinTheColumn() {
		long pid = ProcessHandle.current().pid();

		WorkerIdentity identity = WorkerIdentity.ofThisProcess();

		assertThat(identity.pid()).isEqualTo(pid);
		assertThat(identity.id()).startsWith(Text.cut(identity.hostname(), 32) + "-" + pid + "-")
			.matches(".{1,32}-" + pid + "-[0-9a-f]{8}")
			.hasSizeLessThanOrEqualTo(64);
	}

	@Test
	void ofThisProcess_twice_isTwoWorkers() {
		assertThat(WorkerIdentity.ofThisProcess().id()).isNotEqualTo(WorkerIdentity.ofThisProcess().id());
	}

}
