package io.archsim.app.limits;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import io.archsim.app.access.Principal;
import io.archsim.app.access.Principals;
import io.archsim.app.web.ApiBody;
import io.archsim.app.web.ApiException;

/**
 * Keeps the requests that bring work from filling the API's heap (ADR-0108). From before
 * its body is read until it is answered, such a request holds a slot, so only so many are
 * in memory at once; one that finds no slot free within a second is answered {@code 503}
 * with {@code Retry-After}.
 * <ul>
 * <li>A request to one of the engine's routes ({@link EngineRoute}) first counts against
 * its asker's requests to the engine, then holds a quick slot, or for a calibration,
 * which replays a design for up to minutes, a replay slot, so that it never holds one the
 * quick routes need.</li>
 * <li>A request to any other route that reads a body ({@link ApiBody}) takes one of up to
 * {@value #SMALL_BODY_BYTES} bytes ({@code 413} beyond) and holds a slot of its own
 * kind.</li>
 * </ul>
 * Every such route is synchronous: a slot is given back when the request completes.
 */
class RequestSlots implements HandlerInterceptor {

	/**
	 * The largest body a route that is not the engine's takes, in kilobytes of 1,000
	 * bytes.
	 */
	static final int SMALL_BODY_KILOBYTES = 64;

	/** The largest body a route that is not the engine's takes, in bytes. */
	static final long SMALL_BODY_BYTES = SMALL_BODY_KILOBYTES * 1_000L;

	/** How long a request waits for a slot before it is told to come back. */
	private static final long WAIT_MILLIS = 1000;

	/** Where a request keeps the slots it took one of, to give it back. */
	private static final String HELD = RequestSlots.class.getName() + ".held";

	private final Principals principals;

	private final RateLimits limits;

	private final Semaphore quick;

	private final Semaphore replays;

	private final Semaphore bodies;

	/**
	 * The slots.
	 * @param principals who is asking, by request
	 * @param limits the counters of each person's requests to the engine
	 * @param sizes how many requests of each kind are answered at once
	 */
	RequestSlots(Principals principals, RateLimits limits, Sizes sizes) {
		this.principals = principals;
		this.limits = limits;
		this.quick = new Semaphore(sizes.quick(), true);
		this.replays = new Semaphore(sizes.replays(), true);
		this.bodies = new Semaphore(sizes.bodies(), true);
	}

	/**
	 * Takes a slot for a request that brings work, and counts a request to the engine
	 * against its asker first.
	 * @throws ApiException {@code 401} for a request to the engine that signs no one in,
	 * {@code 413} for a body too large for its route, {@code 429} with
	 * {@code Retry-After} after many requests to the engine this minute, {@code 503} with
	 * {@code Retry-After} when no slot came free in time
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)) {
			return true;
		}
		EngineRoute route = method.getMethodAnnotation(EngineRoute.class);
		if (route != null) {
			Principal principal = this.principals.of(request);
			this.limits.enforce("user:" + principal.userId() + ":engine", RateLimit.ENGINE_REQUESTS_PER_USER,
					"You have sent many designs to the engine this minute.");
			if (route.replay()) {
				hold(request, this.replays, ServerBusy::replays);
			}
			else {
				hold(request, this.quick, ServerBusy::designs);
			}
		}
		else if (readsABody(method)) {
			if (request.getContentLengthLong() > SMALL_BODY_BYTES) {
				throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,
						"Request body too large: this route takes " + SMALL_BODY_KILOBYTES + " KB at most", List.of());
			}
			hold(request, this.bodies, ServerBusy::requests);
		}
		return true;
	}

	/**
	 * Gives the request's slot back, whatever its answer.
	 */
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			@Nullable Exception ex) {
		if (request.getAttribute(HELD) instanceof Semaphore held) {
			request.removeAttribute(HELD);
			held.release();
		}
	}

	private static boolean readsABody(HandlerMethod method) {
		return Arrays.stream(method.getMethodParameters())
			.anyMatch((parameter) -> parameter.hasParameterAnnotation(ApiBody.class));
	}

	/**
	 * Takes one of the slots for a request, waiting a second at most.
	 * @param request the request, which keeps the slot to give it back
	 * @param slots the slots
	 * @param busy the answer when none came free
	 * @throws ApiException the answer given when no slot came free; {@code 503} when the
	 * request is stopped while it waits, which keeps its interrupt
	 */
	private static void hold(HttpServletRequest request, Semaphore slots, Supplier<ApiException> busy) {
		boolean taken;
		try {
			taken = slots.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw ServerBusy.stopping();
		}
		if (!taken) {
			throw busy.get();
		}
		request.setAttribute(HELD, slots);
	}

	/**
	 * How many requests of each kind are answered at once.
	 *
	 * @param quick requests to the engine's quick routes
	 * @param replays calibrations
	 * @param bodies other requests with a body
	 */
	record Sizes(int quick, int replays, int bodies) {

	}

}
