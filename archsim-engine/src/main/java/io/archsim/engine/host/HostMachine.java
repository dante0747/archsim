package io.archsim.engine.host;

import java.util.function.DoublePredicate;
import java.util.function.DoubleUnaryOperator;

import io.archsim.model.document.HostSpec;

/**
 * The CPU of the one machine a design's parts run on ({@code architecture.host}):
 * physical cores with one or two hardware threads each, whose threads slow each other
 * down. With {@code busy} threads wanting the CPU on {@code P} cores of {@code T}
 * threads, a thread runs at {@code clock x sharing x turns} of its speed alone: the clock
 * falls in a straight line from one busy thread to all of them; past {@code P} busy
 * threads two share a core, at the sibling speed; past {@code P x T} they take turns.
 *
 * @param cores the physical cores
 * @param threadsPerCore hardware threads per core, 1 or 2
 * @param siblingSpeed the speed of a thread whose core's other thread is busy
 * @param allCoreClock the clock with every thread busy, as a share of one thread's
 * @param otherBusyThreads threads the machine keeps busy outside the design's parts
 * @param clientCpuMs CPU the clients' traffic costs on the machine per request, in ms
 */
public record HostMachine(double cores, int threadsPerCore, double siblingSpeed, double allCoreClock,
		double otherBusyThreads, double clientCpuMs) {

	/**
	 * The busy threads searched for are at most this many times the machine's threads.
	 */
	private static final double MOST_THREADS_MULTIPLE = 64.0;

	private static final int BISECTIONS = 80;

	private static final double FEWEST_CORES = 1e-9;

	private static final double MILLIS_PER_SECOND = 1000.0;

	/** A multiple of a load this small that fills a part's threads fills them at once. */
	private static final double FILLED_AT_ONCE = 1e-12;

	/**
	 * The machine a design's host describes.
	 * @param host the host
	 * @return its CPU
	 */
	public static HostMachine of(HostSpec host) {
		return new HostMachine(host.physicalCores(), host.threadsPerCore(), host.siblingSpeed(), host.allCoreClock(),
				host.otherBusyThreads(), host.clientCpuMs());
	}

	/**
	 * A part's own vCPUs as hyperthreads, two per physical core, at full clock and with
	 * nothing else on them.
	 * @param cores the part's vCPU
	 * @param siblingSpeed the speed of a vCPU whose sibling is busy too
	 * @return the machine
	 */
	public static HostMachine ownHyperthreads(double cores, double siblingSpeed) {
		return new HostMachine(cores / 2.0, 2, siblingSpeed, 1.0, 0.0, 0.0);
	}

	/**
	 * Its hardware threads.
	 * @return cores times threads per core
	 */
	public double threads() {
		return this.cores * this.threadsPerCore;
	}

	/**
	 * The mean speed of a busy thread when {@code busy} threads want the CPU, it
	 * included.
	 * @param busy the busy threads
	 * @return the speed, as a share of one thread's alone on the idle machine
	 */
	public double speed(double busy) {
		if (busy <= 1.0) {
			return 1.0;
		}
		double threads = threads();
		double clock = 1.0;
		if (threads > 1.0 && this.allCoreClock < 1.0) {
			clock = 1.0 - (1.0 - this.allCoreClock) * (Math.min(threads, busy) - 1.0) / (threads - 1.0);
		}
		double sharing = 1.0;
		double physical = Math.max(this.cores, FEWEST_CORES);
		if (this.threadsPerCore > 1 && busy > physical && this.siblingSpeed < 1.0) {
			sharing = 1.0 - (1.0 - this.siblingSpeed) * Math.min(1.0, 2.0 * (busy - physical) / busy);
		}
		return clock * sharing * Math.min(1.0, threads / busy);
	}

	/**
	 * The threads busy outside the design's parts: the background and the clients'
	 * traffic.
	 * @param clientRps the clients' requests per second
	 * @return the busy threads
	 */
	public double other(double clientRps) {
		return this.otherBusyThreads + Math.max(0.0, clientRps) * this.clientCpuMs / MILLIS_PER_SECOND;
	}

	/**
	 * The busy threads and their speed when the parts' CPU work needs {@code baseBusy}
	 * threads at full speed: the {@code busy} with {@code busy = baseBusy / speed(busy) +
	 * other}. Past what the machine can do, the busy threads are capped at 64 times its
	 * threads.
	 * @param baseBusy the threads the parts need at full speed
	 * @param clientRps the clients' requests per second
	 * @return the busy threads and their speed
	 */
	public HostLoad solve(double baseBusy, double clientRps) {
		double other = other(clientRps);
		double low = Math.max(0.0, baseBusy) + other;
		if (baseBusy <= 0) {
			return new HostLoad(low, speed(low));
		}
		double high = MOST_THREADS_MULTIPLE * Math.max(threads(), 1.0) + low;
		DoubleUnaryOperator gap = (busy) -> baseBusy / speed(busy) + other - busy;
		if (gap.applyAsDouble(high) > 0) {
			return new HostLoad(high, speed(high));
		}
		for (int i = 0; i < BISECTIONS; i++) {
			double middle = (low + high) / 2;
			if (gap.applyAsDouble(middle) > 0) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		return new HostLoad(high, speed(high));
	}

	/**
	 * The multiple of a load at which one part's threads fill: the part needs
	 * {@code ownBase} threads at full speed and has {@code ownThreads}; the machine's
	 * parts need {@code baseBusy} (the part's included) and the clients send
	 * {@code clientRps}, and all of it grows in proportion.
	 * @param baseBusy the threads every part needs at full speed
	 * @param ownBase the threads the part needs at full speed
	 * @param ownThreads the part's vCPUs
	 * @param clientRps the clients' requests per second
	 * @return the multiple; zero when the part's threads are full already; infinite
	 * without CPU work
	 */
	public double scaleFilling(double baseBusy, double ownBase, double ownThreads, double clientRps) {
		if (ownBase <= 0 || ownThreads <= 0) {
			return Double.POSITIVE_INFINITY;
		}
		DoublePredicate fills = (multiple) -> multiple * ownBase
				/ solve(multiple * baseBusy, multiple * clientRps).speed() >= ownThreads;
		double low = 0.0;
		double high = ownThreads / ownBase;
		if (fills.test(low + FILLED_AT_ONCE)) {
			return 0.0;
		}
		// a thread never runs above full speed, so the threads fill by this multiple; the
		// doubling is kept as the Python engine keeps it, against a speed above 1
		while (!fills.test(high)) {
			high *= 2.0;
		}
		for (int i = 0; i < BISECTIONS; i++) {
			double middle = (low + high) / 2;
			if (fills.test(middle)) {
				high = middle;
			}
			else {
				low = middle;
			}
		}
		return high;
	}

}
