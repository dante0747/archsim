package io.archsim.benchmarks.machine;

import java.util.Arrays;
import java.util.Optional;

/**
 * The CPU time Docker's VM has spent, from {@code /proc/stat} inside a container, in
 * jiffies.
 *
 * @param steal the time the hypervisor gave to others while the VM wanted to run
 * @param total the time of the first eight counters, steal included
 */
record CpuTimes(long steal, long total) {

	/** user, nice, system, idle, iowait, irq, softirq and steal. */
	private static final int COUNTERS = 8;

	private static final int STEAL = 7;

	/**
	 * The times the {@code cpu} line of {@code /proc/stat} gives.
	 * @param procStat the file's text
	 * @return the times; empty without the line
	 */
	static Optional<CpuTimes> of(String procStat) {
		return procStat.lines().filter((line) -> line.startsWith("cpu ")).findFirst().map((line) -> {
			long[] counters = Arrays.stream(line.split("\\s+")).skip(1).mapToLong(Long::parseLong).toArray();
			long steal = (counters.length > STEAL) ? counters[STEAL] : 0;
			return new CpuTimes(steal, Arrays.stream(counters).limit(COUNTERS).sum());
		});
	}

	/**
	 * The share of the VM's time lost to steal since an earlier reading.
	 * @param before the earlier reading
	 * @return the steal over the time, at least one jiffy
	 */
	double stealSince(CpuTimes before) {
		return (double) (this.steal - before.steal()) / Math.max(this.total - before.total(), 1);
	}

}
