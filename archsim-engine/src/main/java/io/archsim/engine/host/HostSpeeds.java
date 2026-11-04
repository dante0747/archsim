package io.archsim.engine.host;

import java.util.LinkedHashMap;
import java.util.Map;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.document.HostSpec;

/** The speed each part's CPU work runs at on the design's host machine, at a load. */
public final class HostSpeeds {

	private HostSpeeds() {
	}

	/**
	 * The speed of every part with CPU work on the design's host: each part's threads at
	 * the machine's speed for all their busy threads, the background and the clients'
	 * traffic. Parts at full speed are left out.
	 * @param host the design's host
	 * @param parts each part's work, by its id
	 * @param clientRps the clients' requests per second
	 * @return the speed of each slowed part, by its id
	 */
	public static Map<String, Double> onHost(HostSpec host, Map<String, PartWork> parts, double clientRps) {
		double baseBusy = FloatSum.of(parts.values().stream().mapToDouble(PartWork::busy));
		double speed = HostMachine.of(host).solve(baseBusy, clientRps).speed();
		Map<String, Double> speeds = new LinkedHashMap<>();
		if (speed < 1.0) {
			parts.keySet().forEach((id) -> speeds.put(id, speed));
		}
		return speeds;
	}

	/**
	 * The speed of each part on its own hyperthreads, when the design has no host: a part
	 * with a sibling speed set and at least 2 vCPU runs on {@code vCPU / 2} cores of two
	 * threads each, at their speed for its busiest instance. Parts at full speed are left
	 * out.
	 * @param parts each part's work, by its id
	 * @param siblingSpeeds the speed of a part's thread when its sibling thread is busy,
	 * by its id, for the parts that set one
	 * @return the speed of each slowed part, by its id
	 */
	public static Map<String, Double> onOwnThreads(Map<String, PartWork> parts, Map<String, Double> siblingSpeeds) {
		Map<String, Double> speeds = new LinkedHashMap<>();
		parts.forEach((id, work) -> {
			Double sibling = siblingSpeeds.get(id);
			if (sibling != null && work.cores() >= 2 && sibling < 1.0) {
				HostMachine own = new HostMachine(work.cores() / 2.0, 2, sibling, 1.0, 0.0, 0.0);
				double speed = own.solve(work.busiest(), 0.0).speed();
				if (speed < 1.0) {
					speeds.put(id, speed);
				}
			}
		});
		return speeds;
	}

}
