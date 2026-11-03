package io.archsim.app.calibration;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.engine.OutboundPolicy;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;
import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.UsedUpLimits;
import io.archsim.app.web.JsonText;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Calibrations on a hosted server: who may run them, how many an hour, what the engine
 * service is sent, and how its refusals are answered. Rate limits are on.
 */
@TestPropertySource(properties = { "archsim.rate-limits=true", "archsim.prometheus-allowed-hosts=prom:9090" })
class CalibrationsTests extends HostedTestSupport {

	private static final String OTLP = """
			{"document": {"metadata": {"name": "Shop"}}, "source": "otlp", "traces": {"resourceSpans": []},
			 "step": 15}""";

	@MockitoBean
	private EngineService engine;

	@Test
	void calibrate_byViewer_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn viv = signedIn("Viv");
		member(ada.workspaceId(), viv.userId(), "viewer");

		assertThat(calibrate(viv, ada.workspaceId(), OTLP)).hasStatus(403);
		then(this.engine).shouldHaveNoInteractions();
	}

	@Test
	void calibrate_byViewer_isNotCountedAgainstTheHour() {
		SignedIn ada = signedIn("Ada");
		SignedIn viv = signedIn("Viv");
		member(ada.workspaceId(), viv.userId(), "viewer");

		calibrate(viv, ada.workspaceId(), OTLP);

		assertThat(this.jdbc()
			.sql("SELECT count(*) FROM rate_limits WHERE key = :key")
			.param("key", "user:" + viv.userId() + ":calibrate")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void calibrate_byEditor_sendsTheRequestWithTheWorkspacesPolicy() {
		SignedIn ada = signedIn("Ada");
		given(this.engine.calibrate(any(), any(), any())).willReturn(new JsonText("{}"));

		assertThat(calibrate(ada, ada.workspaceId(), OTLP)).hasStatus(200);

		ArgumentCaptor<ObjectNode> request = ArgumentCaptor.captor();
		then(this.engine).should()
			.calibrate(request.capture(),
					eq(new OutboundPolicy(Outbound.ALLOWLIST, List.of("prom:9090"), 30.0, 52_428_800L)),
					eq(new RunLimits(2_000_000, 20.0)));
		assertThat(request.getValue()).isEqualTo(json("""
				{"document": {"metadata": {"name": "Shop"}}, "source": "otlp", "mapping": null, "prometheusUrl": null,
				 "recorded": null, "range": null, "step": 15.0, "traces": {"resourceSpans": []}, "replay": true}"""));
	}

	@Test
	void calibrate_pastTheHourlyLimit_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		UsedUpLimits.useUp(jdbc(), "user:" + ada.userId() + ":calibrate", RateLimit.CALIBRATIONS_PER_USER);

		assertThat(calibrate(ada, ada.workspaceId(), OTLP)).hasStatus(429)
			.containsHeader(HttpHeaders.RETRY_AFTER)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("You have run many calibrations this hour.");
	}

	@Test
	void calibrate_designTheEngineCannotRead_isRefusedWithItsIssues() {
		SignedIn ada = signedIn("Ada");
		given(this.engine.calibrate(any(), any(), any()))
			.willThrow(EngineRefusalException.invalidDesign("x", List.of(Issue.error("document.schema", "bad"))));

		MvcTestResult result = calibrate(ada, ada.workspaceId(), OTLP);

		assertThat(result).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("The document cannot be calibrated");
		assertThat(result).bodyJson().extractingPath("$.detail.issues[0].code").isEqualTo("document.schema");
	}

	@Test
	void calibrate_telemetryItCannotUse_isRefusedInTheEnginesWords() {
		SignedIn ada = signedIn("Ada");
		given(this.engine.calibrate(any(), any(), any()))
			.willThrow(EngineRefusalException.unreadable("An OpenTelemetry calibration needs the traces (OTLP JSON)"));

		assertThat(calibrate(ada, ada.workspaceId(), OTLP)).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("An OpenTelemetry calibration needs the traces (OTLP JSON)");
	}

	private MvcTestResult calibrate(SignedIn person, String workspaceId, String body) {
		return send(person, HttpMethod.POST, "/api/v1/calibrate?workspace=" + workspaceId, body);
	}

}
