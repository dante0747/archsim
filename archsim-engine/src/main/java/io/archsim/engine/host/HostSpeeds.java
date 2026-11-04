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
	 * The speed of every part with CPU work on the host, for sizing: each part's threads
	 * at the machine's speed for all their busy threads, the background and the clients'
	 * traffic. Parts at full speed are left out.
	 * @param host the design's host
	 * @param parts each part's work, by its id
	 * @param clientRps the clients' requests per second
	 * @return the speed of each slowed part, by its id
	 */
	public static Map<String, Double> forSizing(HostSpec host, Map<String, PartWork> parts, double clientRps) {
		double baseBusy = FloatSum.of(parts.values().stream().mapToDouble(PartWork::busy));
		double speed = HostMachine.of(host).solve(baseBusy, clientRps).speed();
		Map<String, Double> speeds = new LinkedHashMap<>();
		if (speed < 1.0) {
			parts.keySet().forEach((id) -> speeds.put(id, speed));
		}
		return speeds;
	}

}
