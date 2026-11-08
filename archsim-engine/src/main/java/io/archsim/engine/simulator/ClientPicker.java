package io.archsim.engine.simulator;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.runtime.SimContext;

/**
 * Picks the client group that sends each request, in proportion to the groups' shares. A
 * run with one group draws nothing, so adding a group changes nothing else of the run.
 */
final class ClientPicker {

	private final List<TrafficSourceRuntime> groups;

	private final double[] cumulative;

	private final double total;

	private final @Nullable PythonRandom stream;

	ClientPicker(SimContext context, List<TrafficSourceRuntime> groups) {
		this.groups = List.copyOf(groups);
		this.cumulative = new double[groups.size()];
		double sum = 0;
		for (int index = 0; index < this.cumulative.length; index++) {
			sum += groups.get(index).config().trafficShare();
			this.cumulative[index] = sum;
		}
		this.total = sum;
		this.stream = (groups.size() > 1) ? context.stream("clients") : null;
	}

	/**
	 * The group that sends the next request.
	 * @return the group
	 */
	TrafficSourceRuntime next() {
		if (this.stream == null) {
			return this.groups.getFirst();
		}
		int picked = bisectLeft(this.stream.nextDouble() * this.total);
		return this.groups.get(Math.min(picked, this.groups.size() - 1));
	}

	private int bisectLeft(double target) {
		int low = 0;
		int high = this.cumulative.length;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (this.cumulative[middle] < target) {
				low = middle + 1;
			}
			else {
				high = middle;
			}
		}
		return low;
	}

}
