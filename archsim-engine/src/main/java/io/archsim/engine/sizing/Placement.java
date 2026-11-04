package io.archsim.engine.sizing;

/**
 * Where a part runs, as its size depends on it.
 *
 * @param onVm it runs on a VM of its own, whose operating system needs memory too
 * @param speed its CPU work's speed on the design's host machine, as a share of full
 * speed
 */
record Placement(boolean onVm, double speed) {

	/**
	 * The busy cores at the part's speed, and the words for them.
	 * @param busy the cores busy at full speed
	 * @param intro how they were worked out
	 * @return the cores busy at the part's speed, and the explanation so far
	 */
	Work atSpeed(double busy, String intro) {
		if (this.speed >= 1.0) {
			return new Work(busy, intro);
		}
		return new Work(busy / this.speed,
				intro + ", at " + SizingText.brief(this.speed) + " of full speed on the host machine");
	}

	/**
	 * The CPU cores a part keeps busy at the peak.
	 *
	 * @param busy the busy cores
	 * @param intro how they were worked out
	 */
	record Work(double busy, String intro) {
	}

}
