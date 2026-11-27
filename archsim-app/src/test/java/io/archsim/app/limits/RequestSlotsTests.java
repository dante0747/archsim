package io.archsim.app.limits;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import io.archsim.app.access.Principal;
import io.archsim.app.access.Principals;
import io.archsim.app.web.ApiBody;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The request slots one at a time, one slot of each kind: a calibration never takes a
 * quick request's slot, a request stopped while it waits keeps its interrupt, an async
 * dispatch takes nothing, and a route that is not the engine's takes a small body under a
 * slot of its own (ADR-0108).
 */
class RequestSlotsTests {

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final Principals principals = mock();

	private final RequestSlots slots = new RequestSlots(this.principals, mock(), new RequestSlots.Sizes(1, 1, 1));

	@BeforeEach
	void theLocalUserAsks() {
		given(this.principals.of(any())).willReturn(Principal.local(null));
	}

	@Test
	void preHandle_aCalibrationWhileTheOnlyReplaySlotIsHeld_isBusyForTenSeconds() {
		assertThat(this.slots.preHandle(request(), this.response, handler("calibrate"))).isTrue();

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.slots.preHandle(request(), this.response, handler("calibrate")))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(ex.headers().getFirst("Retry-After")).isEqualTo("10");
			})
			.withMessage("The server is replaying other calibrations: calibrate again in a moment");
	}

	@Test
	void preHandle_aQuickRouteWhileTheOnlyReplaySlotIsHeld_isServed() {
		this.slots.preHandle(request(), this.response, handler("calibrate"));

		assertThat(this.slots.preHandle(request(), this.response, handler("validate"))).isTrue();
	}

	@Test
	void afterCompletion_ofTheRequestHoldingTheSlot_givesItBack() {
		MockHttpServletRequest holding = request();
		this.slots.preHandle(holding, this.response, handler("validate"));

		this.slots.afterCompletion(holding, this.response, handler("validate"), null);

		assertThat(this.slots.preHandle(request(), this.response, handler("validate"))).isTrue();
	}

	@Test
	void preHandle_interruptedWhileItWaits_isUnavailableAndKeepsTheInterrupt() throws InterruptedException {
		this.slots.preHandle(request(), this.response, handler("validate"));
		AtomicReference<ApiException> refused = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread waiting = Thread.ofPlatform().start(() -> {
			Thread.currentThread().interrupt();
			try {
				this.slots.preHandle(request(), this.response, handler("validate"));
			}
			catch (ApiException ex) {
				refused.set(ex);
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});

		waiting.join(10_000);

		assertThat(refused).hasValueSatisfying((ex) -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.getMessage()).isEqualTo("The server is stopping: try again in a moment");
		});
		assertThat(interrupted).isTrue();
	}

	@Test
	void preHandle_anAsyncDispatchWhileTheOnlySlotIsHeld_takesNone() {
		this.slots.preHandle(request(), this.response, handler("validate"));
		MockHttpServletRequest async = request();
		async.setDispatcherType(DispatcherType.ASYNC);

		assertThat(this.slots.preHandle(async, this.response, handler("validate"))).isTrue();
	}

	@Test
	void preHandle_aRouteWithABodyOverItsCap_isTooLarge() {
		MockHttpServletRequest large = request();
		large.setContent(new byte[64_001]);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.slots.preHandle(large, this.response, handler("comment")))
			.satisfies((ex) -> assertThat(ex.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE))
			.withMessage("Request body too large: this route takes 64 KB at most");
	}

	@Test
	void preHandle_aRouteWithABodyWhileItsOnlySlotIsHeld_isBusyForASecond() {
		this.slots.preHandle(request(), this.response, handler("comment"));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.slots.preHandle(request(), this.response, handler("comment")))
			.satisfies((ex) -> assertThat(ex.headers().getFirst("Retry-After")).isEqualTo("1"))
			.withMessage("The server is busy: try again in a moment");
	}

	@Test
	void preHandle_aRouteWithoutABodyWhileEverySlotIsHeld_takesNone() {
		this.slots.preHandle(request(), this.response, handler("comment"));
		this.slots.preHandle(request(), this.response, handler("validate"));
		this.slots.preHandle(request(), this.response, handler("calibrate"));

		assertThat(this.slots.preHandle(request(), this.response, handler("read"))).isTrue();
	}

	private static MockHttpServletRequest request() {
		return new MockHttpServletRequest("POST", "/api/v1/things");
	}

	private static HandlerMethod handler(String name) {
		Method method = Arrays.stream(Routes.class.getDeclaredMethods())
			.filter((candidate) -> candidate.getName().equals(name))
			.findFirst()
			.orElseThrow();
		return new HandlerMethod(new Routes(), method);
	}

	/** Routes of each kind, as the interceptor sees them. */
	static class Routes {

		@EngineRoute
		void validate() {
		}

		@EngineRoute(replay = true)
		void calibrate() {
		}

		void comment(@ApiBody Map<String, Object> body) {
		}

		void read() {
		}

	}

}
