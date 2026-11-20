package io.archsim.benchmarks.machine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.host.HostMachine;

/**
 * The machine's thread model, fitted to a contention check (run.py's {@code host_fit}):
 * every container of a stack, k6 and Windows share the laptop's physical cores, and CPU
 * work slows as they fill.
 *
 * @param physicalCores the distinct cores of the VM's topology
 * @param threadsPerCore its threads per core
 * @param siblingSpeed the speed of a thread sharing its core, as a share of its speed
 * alone
 * @param allCoreClock the clock with every thread busy, as a share of one thread's
 * @param backgroundThreads the machine's busy threads with the stack up and at rest
 * @param k6CpuMsPerRequest k6's CPU per request, in milliseconds
 * @param how where each part came from, in words
 */
public record MachineFit(int physicalCores, int threadsPerCore, double siblingSpeed, double allCoreClock,
		double backgroundThreads, double k6CpuMsPerRequest, String how) {

	private static final int DIGEST_DIGITS = 16;

	private static final String HOW = "how";

	/**
	 * A fit as {@link #json()} writes it, or as a what-if file gives it.
	 * @param fit the fit's keys
	 * @return the fit
	 */
	public static MachineFit of(JsonNode fit) {
		JsonNode how = fit.path(HOW);
		if (!how.isString()) {
			throw new IllegalArgumentException("a machine's fit needs its how, in words");
		}
		return new MachineFit(number(fit, "physicalCores").intValue(), number(fit, "threadsPerCore").intValue(),
				number(fit, "siblingSpeed").doubleValue(), number(fit, "allCoreClock").doubleValue(),
				number(fit, "backgroundThreads").doubleValue(), number(fit, "k6CpuMsPerRequest").doubleValue(),
				how.stringValue());
	}

	/**
	 * A digest of the fit's numbers, which tells the work done on one machine from the
	 * work done on another. How the fit came about is left out: two fits that simulate
	 * alike share it, and a freeze that names it (ADR-0095) outlives a harness that words
	 * it otherwise.
	 * @return the SHA-256 of the fit's JSON without its how, its first 16 hexadecimal
	 * digits
	 */
	public String digest() {
		ObjectNode numbers = json();
		numbers.remove(HOW);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(numbers.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, DIGEST_DIGITS);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("this Java has no SHA-256", ex);
		}
	}

	/**
	 * The fit's keys, in run.py's order.
	 * @return the fit
	 */
	public ObjectNode json() {
		return JsonNodeFactory.instance.objectNode()
			.put("physicalCores", this.physicalCores)
			.put("threadsPerCore", this.threadsPerCore)
			.put("siblingSpeed", this.siblingSpeed)
			.put("allCoreClock", this.allCoreClock)
			.put("backgroundThreads", this.backgroundThreads)
			.put("k6CpuMsPerRequest", this.k6CpuMsPerRequest)
			.put(HOW, this.how);
	}

	/**
	 * The mean speed of a busy thread when so many want the CPU, with nothing else on the
	 * machine (the engine's {@code Machine.speed}).
	 * @param busy the busy threads
	 * @return the speed, as a share of one thread's alone on the idle machine
	 */
	public double speed(double busy) {
		return new HostMachine(this.physicalCores, this.threadsPerCore, this.siblingSpeed, this.allCoreClock, 0, 0)
			.speed(busy);
	}

	private static JsonNode number(JsonNode fit, String key) {
		JsonNode value = fit.path(key);
		if (!value.isNumber()) {
			throw new IllegalArgumentException("a machine's fit needs a number " + key + ", not " + value);
		}
		return value;
	}

	/**
	 * The fit with its all-core clock set for a what-if.
	 * @param clock the clock, as given
	 * @return the fit
	 */
	public MachineFit withClock(String clock) {
		return new MachineFit(this.physicalCores, this.threadsPerCore, this.siblingSpeed, Double.parseDouble(clock),
				this.backgroundThreads, this.k6CpuMsPerRequest, this.how + "; clock set to " + clock + " (what-if)");
	}

}
