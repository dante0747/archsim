package io.archsim.engine.runtime;

import java.util.function.Consumer;

/**
 * What handles a request at the end of a link: usually a part's
 * {@link RuntimeComponent#handle}, or one of its instances.
 */
@FunctionalInterface
public interface Handler {

	/**
	 * Handles a request.
	 * @param request the request
	 * @param done what to do with how it ended
	 */
	void handle(Request request, Consumer<Outcome> done);

}
