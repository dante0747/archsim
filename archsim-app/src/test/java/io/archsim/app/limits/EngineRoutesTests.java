package io.archsim.app.limits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every route that makes the engine work on a design, from its body or as stored, holds
 * one of the engine's slots: a quick slot, or a replay slot for a calibration (ADR-0108).
 */
class EngineRoutesTests extends HostedTestSupport {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping routes;

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "POST, /api/v1/validate", "POST, /api/v1/analyze", "POST, /api/v1/export", "POST, /api/v1/import",
			"POST, /api/v1/projects", "POST, /api/v1/projects/import", "PUT, /api/v1/projects/p1",
			"POST, /api/v1/projects/p1/runs", "POST, /api/v1/projects/p1/runs/batch",
			"POST, /api/v1/projects/p1/duplicate", "POST, /api/v1/projects/from-share", "POST, /api/v1/runs/r1/rerun" })
	void route_readingADesign_holdsAQuickSlot(String method, String path) throws Exception {
		assertThat(route(method, path).replay()).isFalse();
	}

	@Test
	void calibrate_replayingADesign_holdsAReplaySlot() throws Exception {
		assertThat(route("POST", "/api/v1/calibrate").replay()).isTrue();
	}

	private EngineRoute route(String method, String path) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		ServletRequestPathUtils.parseAndCache(request);
		HandlerExecutionChain chain = this.routes.getHandler(request);
		if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) {
			return fail("no route answers " + method + " " + path);
		}
		EngineRoute route = handler.getMethodAnnotation(EngineRoute.class);
		if (route == null) {
			return fail(method + " " + path + " holds no engine slot");
		}
		return route;
	}

}
