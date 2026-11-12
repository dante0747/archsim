package io.archsim.app.analysis;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.security.SecurityConfiguration;
import io.archsim.app.web.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@code POST /validate} and {@code POST /analyze}: their bodies in, the answers out
 * unchanged.
 */
@WebMvcTest(AnalysisController.class)
@Import(SecurityConfiguration.class)
class AnalysisControllerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private AnalysisService analysis;

	@Test
	void analyze_design_answersTheEnginesJsonAsWritten() {
		given(this.analysis.analyze(any())).willReturn(new JsonText("{\"peakRps\":600,\"tiny\":1e-05}"));

		assertThat(this.mvc.post().uri("/api/v1/analyze").contentType(MediaType.APPLICATION_JSON).content("""
				{"document": {}}""")).hasStatusOk()
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.hasBodyTextEqualTo("{\"peakRps\":600,\"tiny\":1e-05}");
	}

	@Test
	void validate_requestWithScenario_readsBothFields() {
		given(this.analysis.validate(new AnalysisRequest(JSON.createObjectNode(), "peak")))
			.willReturn(new JsonText("{\"valid\":true,\"issues\":[]}"));

		assertThat(this.mvc.post().uri("/api/v1/validate").contentType(MediaType.APPLICATION_JSON).content("""
				{"document": {}, "scenarioId": "peak"}""")).hasStatusOk()
			.hasBodyTextEqualTo("{\"valid\":true,\"issues\":[]}");
	}

	@Test
	void validate_requestWithoutDocument_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc.post().uri("/api/v1/validate").contentType(MediaType.APPLICATION_JSON).content("""
				{"scenarioId": "peak"}""")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("body", "document"));
	}

}
