package io.archsim.app.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which queued runs a claim takes: in the candidates' order, which puts every workspace's
 * oldest run before anyone's second, skipping a workspace at its cap of runs at once, up
 * to the claim's limit. One busy workspace cannot hold every slot.
 */
final class FairPick {

	private FairPick() {
	}

	/**
	 * Picks the runs to take.
	 * @param candidates the queued runs, each workspace's oldest first, then their second
	 * oldest, and so on
	 * @param caps the cap of runs at once of each workspace that has one
	 * @param running the runs each workspace has running now, on every worker
	 * @param limit the most runs to take
	 * @return the runs, in the order picked
	 */
	static List<String> pick(List<Candidate> candidates, Map<String, Integer> caps, Map<String, Integer> running,
			int limit) {
		Map<String, Integer> busy = new HashMap<>(running);
		List<String> picked = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (picked.size() == limit) {
				break;
			}
			String workspace = candidate.workspaceId();
			int atOnce = busy.getOrDefault(workspace, 0);
			if (atOnce < caps.getOrDefault(workspace, Integer.MAX_VALUE)) {
				busy.put(workspace, atOnce + 1);
				picked.add(candidate.runId());
			}
		}
		return picked;
	}

}
