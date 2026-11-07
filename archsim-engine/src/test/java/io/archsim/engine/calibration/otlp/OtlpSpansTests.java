package io.archsim.engine.calibration.otlp;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * OTLP exports read as the Python engine's {@code parse_otlp} reads them, where the
 * golden files do not reach. Expected values are the Python engine's.
 */
class OtlpSpansTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPAN = "\"traceId\": \"t\", \"spanId\": \"s1\", \"startTimeUnixNano\": \"10\", "
			+ "\"endTimeUnixNano\": \"30\"";

	@Test
	void parse_kinds_areNamedByNumberOrWithoutTheirPrefix() {
		List<Span> spans = OtlpSpans.parse(JSON.readTree("""
				{"resourceSpans": [{"instrumentationLibrarySpans": [{"spans": [
				  {%1$s, "kind": 2}, {%1$s, "kind": "SPAN_KIND_CLIENT"}, {%1$s, "kind": 9}, {%1$s},
				  {%1$s, "kind": true}, {%1$s, "kind": 2.0}, {%1$s, "kind": null}]}]}]}""".formatted(SPAN)));

		assertThat(spans).extracting(Span::kind)
			.containsExactly("SERVER", "CLIENT", "9", "INTERNAL", "INTERNAL", "2.0", "None");
	}

	@Test
	void parse_resourceAndAttributes_areReadAsPythonReadsThem() {
		List<Span> spans = OtlpSpans.parse(JSON.readTree("""
				{"resourceSpans": [
				  {"resource": {"attributes": [{"key": "service.name", "value": {"intValue": "7"}}]},
				   "scopeSpans": [{"spans": [{%1$s, "parentSpanId": "", "attributes": [
				     {"key": "a", "value": {"boolValue": true}}, {"key": "b", "value": {}}, {"key": "c"}]}]}]},
				  {"scopeSpans": [{"spans": [{%1$s}]}]}]}""".formatted(SPAN)));

		assertThat(spans).extracting(Span::service).containsExactly("7", "unknown");
		assertThat(spans.getFirst().attrs()).isEqualTo(JSON.readTree("{\"a\": true, \"b\": null, \"c\": null}"));
		assertThat(spans.getFirst().parentId()).isNull();
		assertThat(spans.getFirst().name()).isEmpty();
	}

	@Test
	void parse_spanWithoutAnId_isPythonsKeyError() {
		assertThatIllegalArgumentException().isThrownBy(() -> OtlpSpans.parse(export("{\"spanId\": \"x\"}")))
			.withMessage("'traceId'");
	}

	@Test
	void parse_timeThatIsNoWholeNumber_isPythonsValueError() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OtlpSpans.parse(export("{" + SPAN.replace("\"10\"", "\"1.5\"") + "}")))
			.withMessage("invalid literal for int() with base 10: '1.5'");
	}

	@Test
	void parse_timeBeyondALong_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> OtlpSpans.parse(export("{" + SPAN.replace("\"10\"", "\"1" + "0".repeat(20) + "\"") + "}")))
			.withMessage("a span time is beyond what the engine reads (the years 1677 to 2262)");
	}

	@Test
	void parse_emptyParentSpanId_isNoParent() {
		Span span = OtlpSpans.parse(export("{" + SPAN + ", \"parentSpanId\": \"\"}")).getFirst();

		assertThat(span.parentId()).isNull();
	}

	@Test
	void parse_attributeValueThatIsNotAnAnyValueObject_isNone() {
		Span span = OtlpSpans
			.parse(export("{" + SPAN + ", \"attributes\": "
					+ "[{\"key\": \"k\", \"value\": \"plain\"}, {\"key\": \"m\", \"value\": [\"a\"]}]}"))
			.getFirst();

		assertThat(span.attrs()).isEqualTo(JSON.readTree("{\"k\": null, \"m\": null}"));
	}

	@ParameterizedTest
	@MethodSource("notExports")
	void parse_dataThatIsNotAnExport_isRefusedAsPythonsCalibrationWordsIt(String data, String pythonError) {
		assertThatExceptionOfType(OtlpExportException.class).isThrownBy(() -> OtlpSpans.parse(JSON.readTree(data)))
			.withMessage("not an OTLP JSON export (" + pythonError + ")");
	}

	/** Data the Python engine's parse_otlp fails on, each with the error it raises. */
	static Stream<Arguments> notExports() {
		String attribute = "AttributeError";
		String type = "TypeError";
		return Stream.of(Arguments.of("[]", attribute), Arguments.of("\"x\"", attribute),
				Arguments.of("{\"resourceSpans\": {\"a\": 1}}", attribute),
				Arguments.of("{\"resourceSpans\": \"abc\"}", attribute), Arguments.of("{\"resourceSpans\": 5}", type),
				Arguments.of("{\"resourceSpans\": true}", type), Arguments.of("{\"resourceSpans\": [5]}", attribute),
				Arguments.of("{\"resourceSpans\": [{\"resource\": [1]}]}", attribute),
				Arguments.of("{\"resourceSpans\": [{\"resource\": 3}]}", attribute),
				Arguments.of(resource("{\"a\": 1}"), type), Arguments.of(resource("2"), type),
				Arguments.of(resource("[\"x\"]"), type), Arguments.of(resource("[null]"), type),
				Arguments.of(resource("[{\"key\": [1]}]"), type),
				Arguments.of(resource("[{\"key\": \"k\", \"value\": 3}]"), type),
				Arguments.of(resource("[{\"key\": \"k\", \"value\": \"xstringValuex\"}]"), type),
				Arguments.of(resource("[{\"key\": \"k\", \"value\": [\"intValue\"]}]"), type),
				Arguments.of("{\"resourceSpans\": [{\"scopeSpans\": {\"a\": 1}}]}", attribute),
				Arguments.of("{\"resourceSpans\": [{\"scopeSpans\": 7}]}", type),
				Arguments.of("{\"resourceSpans\": [{\"scopeSpans\": [7]}]}", attribute),
				Arguments.of("{\"resourceSpans\": [{\"scopeSpans\": [{\"spans\": \"ab\"}]}]}", attribute),
				Arguments.of("{\"resourceSpans\": [{\"scopeSpans\": [{\"spans\": 1.5}]}]}", type),
				Arguments.of(exportText("\"x\""), attribute), Arguments.of(exportText("null"), attribute),
				Arguments.of(exportText("{" + SPAN.replace("\"10\"", "null") + "}"), type),
				Arguments.of(exportText("{" + SPAN.replace("\"10\"", "[1]") + "}"), type),
				Arguments.of(exportText("{" + SPAN + ", \"attributes\": 4}"), type));
	}

	@Test
	void parse_timeThatIsAFloat_isTruncated() {
		assertThat(OtlpSpans.parse(export("{" + SPAN.replace("\"10\"", "12.9") + "}")).getFirst().startNs())
			.isEqualTo(12);
	}

	private static JsonNode export(String span) {
		return JSON.readTree(exportText(span));
	}

	private static String exportText(String span) {
		return "{\"resourceSpans\": [{\"scopeSpans\": [{\"spans\": [" + span + "]}]}]}";
	}

	private static String resource(String attributes) {
		return "{\"resourceSpans\": [{\"resource\": {\"attributes\": " + attributes + "}}]}";
	}

}
