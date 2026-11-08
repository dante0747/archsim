package io.archsim.engine.runtime;

import java.util.List;

/**
 * A sampled user request, once its client is done with it: what it asked, how it ended
 * and the steps it took, for the report's traces. The steps are read when the report is
 * written: an attempt the client gave up on may still add some afterwards.
 *
 * @param clientId the client group that sent it
 * @param request the request, whose trace its attempts share
 * @param end when its client was done with it, in simulated milliseconds
 * @param outcome how it ended
 * @param attempts how many attempts its client made
 */
public record TracedRequest(String clientId, Request request, double end, Outcome outcome, int attempts) {

	/**
	 * The steps the request took, its retries' included.
	 * @return the steps, as recorded so far
	 */
	public List<Span> spans() {
		return this.request.spans();
	}

}
