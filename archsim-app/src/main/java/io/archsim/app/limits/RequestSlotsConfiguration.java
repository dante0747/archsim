package io.archsim.app.limits;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.MappedInterceptor;

import io.archsim.app.access.Principals;
import io.archsim.app.config.ArchsimProperties;

/**
 * Puts the request slots ({@link RequestSlots}) in front of every route. Spring MVC finds
 * the interceptor by its type, {@link MappedInterceptor}; a test of one controller's
 * slice leaves it out, with the rate limits it needs.
 */
@Configuration(proxyBeanMethods = false)
class RequestSlotsConfiguration {

	/** Every path: the interceptor itself tells which routes bring work. */
	private static final String[] EVERY_PATH = { "/**" };

	/** Other requests with a body answered at once: 32 bodies of 64 KB at most. */
	private static final int BODIES = 32;

	/**
	 * The slots, as many as the settings say: {@code ARCHSIM_MAX_ENGINE_REQUESTS} for the
	 * engine's quick routes, as many replays as the heap holds for calibrations, and
	 * {@value #BODIES} for other requests with a body.
	 * @param principals who is asking, by request
	 * @param limits the counters of each person's requests
	 * @param properties the settings
	 * @return the interceptor, on every path
	 */
	@Bean
	MappedInterceptor requestSlots(Principals principals, RateLimits limits, ArchsimProperties properties) {
		return new MappedInterceptor(EVERY_PATH, new RequestSlots(principals, limits,
				new RequestSlots.Sizes(properties.engineRequests(), properties.replaySlots(), BODIES)));
	}

}
