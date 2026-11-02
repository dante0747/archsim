package io.archsim.model.document;

import io.archsim.model.codec.Choice;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;

/**
 * The one machine every part with a CPU size runs on (schema 1.7), such as docker compose
 * on one VM or a benchmark on a laptop: the parts' CPU work, its own background work and
 * the clients' traffic share its physical cores.
 *
 * @param physicalCores physical cores, not hardware threads
 * @param threadsPerCore 2 with hyperthreading, 1 without
 * @param siblingSpeed a thread's speed while its core's other thread is busy too
 * @param allCoreClock the clock with every thread busy, relative to one busy thread
 * @param otherBusyThreads threads busy with work outside the design
 * @param clientCpuMs CPU time each client request costs on the machine outside the
 * design's parts
 */
@Description("""
		The one machine every part with a CPU size runs on (schema 1.7; see
		``components/host.py``). Its parts' CPU work, its own background and the CPU the clients'
		traffic costs on it share its physical cores: past one busy thread per core, threads share
		cores as hyperthreads, and the clock falls as more cores are busy.""")
public record HostSpec(@Description(PHYSICAL_CORES) @Ge(1) @Le(512) int physicalCores,
		@Default("2") @Description(THREADS_PER_CORE) @Choice(1) @Choice(2) int threadsPerCore,
		@Default("0.6") @Description(SIBLING_SPEED) @Gt(0) @Le(1) double siblingSpeed,
		@Default("1.0") @Description(ALL_CORE_CLOCK) @Gt(0) @Le(1) double allCoreClock,
		@Default("0.0") @Description(OTHER_BUSY_THREADS) @Ge(0) @Le(4096) double otherBusyThreads,
		@Default("0.0") @Description(CLIENT_CPU_MS) @Ge(0) @Le(10_000) double clientCpuMs) {

	static final String PHYSICAL_CORES = "Physical cores of the machine (not hardware threads).";

	static final String THREADS_PER_CORE = "Hardware threads per core: 2 with hyperthreading (SMT), 1 without.";

	static final String SIBLING_SPEED = "Speed of a thread while its core's other thread is busy too, relative to "
			+ "running alone: a busy pair does twice this much work (about 0.6 on Intel and AMD cores).";

	static final String ALL_CORE_CLOCK = "Clock with every thread busy, relative to one busy thread (turbo and "
			+ "power limits; about 0.75 on a laptop, close to 1 on servers). In between it falls in a straight line.";

	static final String OTHER_BUSY_THREADS = "Threads busy on the machine with work outside the design (operating "
			+ "system, other programs).";

	static final String CLIENT_CPU_MS = "CPU time each request of the clients' traffic costs on the machine outside "
			+ "the design's parts (ms), e.g. a load generator running on it. 0: the clients are elsewhere.";

}
