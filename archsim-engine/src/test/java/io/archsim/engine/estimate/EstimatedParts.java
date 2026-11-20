package io.archsim.engine.estimate;

/**
 * The estimate of one part, as the estimate tests read it.
 */
final class EstimatedParts {

	private EstimatedParts() {
	}

	/**
	 * A part's estimate.
	 * @param estimate the design's estimate
	 * @param id the part's id
	 * @return its estimate, which has its figures
	 */
	static EstimatedPart part(InstantEstimate estimate, String id) {
		return estimate.parts()
			.stream()
			.filter((part) -> part.id().equals(id))
			.map(EstimatedPart.class::cast)
			.findFirst()
			.orElseThrow();
	}

}
