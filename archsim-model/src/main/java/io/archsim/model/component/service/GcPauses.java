package io.archsim.model.component.service;

/**
 * A runtime's stop-the-world garbage collections, as a service's settings or its
 * technology set them.
 *
 * @param pauseMs the median pause, in milliseconds, above zero
 * @param everyRequests the requests a replica serves between collections, above zero
 */
public record GcPauses(double pauseMs, int everyRequests) {

	/**
	 * Checks that a pause takes time and comes every so many requests.
	 */
	public GcPauses {
		if (!(pauseMs > 0) || everyRequests <= 0) {
			throw new IllegalArgumentException("a runtime's pauses take time and come every so many requests; got "
					+ pauseMs + " ms every " + everyRequests);
		}
	}

}
