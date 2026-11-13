package io.archsim.benchmarks.machine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The machine's thread model from a contention check: it recovers the machine the check
 * was measured on, and takes no check of a busy machine or one without k6's CPU.
 */
class ContentionFitTests {

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	@Test
	void of_aCheckOfAKnownMachine_countsItsCoresFromTheTopology() {
		assertThat(FIT.physicalCores()).isEqualTo(4);
		assertThat(FIT.threadsPerCore()).isEqualTo(2);
	}

	@Test
	void of_aCheckOfAKnownMachine_findsItsAllCoreClock() {
		assertThat(FIT.allCoreClock()).isCloseTo(0.75, within(0.005));
	}

	@Test
	void of_aCheckOfAKnownMachine_findsTheSpeedOfAThreadSharingItsCore() {
		assertThat(FIT.siblingSpeed()).isCloseTo(0.5, within(0.011));
	}

	@Test
	void of_aCheckOfAKnownMachine_takesItsBusyThreadsAtRest() {
		assertThat(FIT.backgroundThreads()).isEqualTo(0.4);
	}

	// the step below 90% of the service's core only: 0.5 cores at 2,500 req/s
	@Test
	void of_aCheckOfAKnownMachine_takesK6sCpuPerRequestBelowTheServicesCore() {
		assertThat(FIT.k6CpuMsPerRequest()).isCloseTo(0.2, within(1e-9));
	}

	@Test
	void of_aCheckOfAKnownMachine_namesTheCheck() {
		assertThat(FIT.how()).contains("contention check 2026-11-01T03:00:00Z");
	}

	@Test
	void of_aCheckTakenOnABusyMachine_isNone() {
		ObjectNode busy = KnownMachine.check(2.5, 0.5);

		assertThat(ContentionFit.of(busy)).isEmpty();
	}

	@Test
	void of_aCheckWithoutTheMachineAtRest_isNone() {
		ObjectNode check = KnownMachine.check();
		check.remove("serviceAtRest");

		assertThat(ContentionFit.of(check)).isEmpty();
	}

	@Test
	void of_aCheckWithoutK6sCpu_isNone() {
		ObjectNode check = KnownMachine.check();
		check.get("service").valueStream().forEach((step) -> ((ObjectNode) step).putNull("k6Cores"));

		assertThat(ContentionFit.of(check)).isEmpty();
	}

	// the probe from one check, the machine at rest and k6 from another
	@Test
	void of_aServicePartFromAnotherCheck_namesBoth() {
		ObjectNode service = KnownMachine.check().put("at", "2026-11-02T03:00:00Z");

		assertThat(ContentionFit.of(KnownMachine.check(), service).orElseThrow().how())
			.startsWith("contention check 2026-11-01T03:00:00Z (service part 2026-11-02T03:00:00Z): ");
	}

}
