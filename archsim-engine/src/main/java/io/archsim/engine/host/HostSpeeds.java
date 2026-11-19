package io.archsim.engine.host;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.Resources;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.HostSpec;
import io.archsim.model.document.NodeSpec;

/** The speed each part's CPU work runs at on the design's host machine, at a load. */
public final class HostSpeeds {

	private HostSpeeds() {
	}

	/**
	 * The speed of each part whose CPU threads slow each other down at a load (schema
	 * 1.7): on the design's host, else on a part's own hyperthreads. Of two parts with
	 * one id, the later counts, as in the Python engine's dictionaries.
	 * @param architecture the design's architecture
	 * @param load each part's load
	 * @param settingsOf each part's settings as the caller reads them; empty for a part
	 * it leaves out
	 * @param clientRps the clients' requests per second
	 * @return the speed of each slowed part, by its id
	 */
	public static Map<String, Double> at(Architecture architecture, LoadEstimate load,
			Function<NodeSpec, Optional<LenientSettings>> settingsOf, double clientRps) {
		Map<String, PartWork> work = new LinkedHashMap<>();
		Map<String, Double> siblingSpeeds = new LinkedHashMap<>();
		for (NodeSpec node : architecture.nodes()) {
			Optional<NodeLoad> carried = load.node(node.id());
			Optional<LenientSettings> settings = settingsOf.apply(node);
			if (carried.isPresent() && settings.isPresent()) {
				PartWork.withCpuSize(node.type(), settings.get(), carried.get())
					.ifPresentOrElse((found) -> work.put(node.id(), found), () -> work.remove(node.id()));
				settings.get().number("siblingSpeed").ifPresent((speed) -> siblingSpeeds.put(node.id(), speed));
			}
		}
		HostSpec host = architecture.host();
		return (host != null) ? onHost(host, work, clientRps) : onOwnThreads(work, siblingSpeeds);
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
	 * Whether a part's vCPUs are hyperthreads, two per physical core, whose sibling slows
	 * them when busy too.
	 * @param cores the part's vCPU
	 * @param siblingSpeed the speed of a vCPU whose sibling is busy too, if set
	 * @return {@code true} for 2 vCPU or more with a sibling speed below 1
	 */
	public static boolean runsOnOwnHyperthreads(double cores, @Nullable Double siblingSpeed) {
		return siblingSpeed != null && cores >= Resources.FEWEST_PAIRED_VCPU && siblingSpeed < 1.0;
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
			if (sibling != null && runsOnOwnHyperthreads(work.cores(), sibling)) {
				HostMachine own = HostMachine.ownHyperthreads(work.cores(), sibling);
				double speed = own.solve(work.busiest(), 0.0).speed();
				if (speed < 1.0) {
					speeds.put(id, speed);
				}
			}
		});
		return speeds;
	}

}
