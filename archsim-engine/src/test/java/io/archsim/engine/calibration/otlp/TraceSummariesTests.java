package io.archsim.engine.calibration.otlp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.otlp.TraceSummary.Call;
import io.archsim.engine.calibration.otlp.TraceSummary.CpuSample;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spans summarized as the Python engine's {@code summarize} summarizes them, where the
 * golden files do not reach. Expected values are the Python engine's.
 */
class TraceSummariesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void summarize_spans_giveEachServicesOwnTimeTheDatabasesAndTheCallGraph() {
		List<Span> spans = List.of(span("a SERVER api - 0 100", "{\"cpu\": 5}"),
				span("b CLIENT api a 10 30", "{\"db.system\": \"postgresql\", \"db.statement\": \"update x\"}"),
				span("c CLIENT api a 20 50", "{}"), span("d SERVER inv c 25 45", "{}"),
				span("e PRODUCER api a 60 70", "{}"), span("f INTERNAL api f 0 1", "{}"),
				span("g CLIENT api a 80 90", "{\"db.system\": \"redis\", \"db.operation\": \"get\"}"));

		TraceSummary summary = TraceSummaries.summarize(spans, new CpuTime("cpu", 1.0));

		assertThat(summary.services()).isEqualTo(Map.of("api", List.of(4e-05), "inv", List.of(2e-05)));
		assertThat(summary.dbReads()).isEqualTo(Map.of("redis", List.of(1e-05)));
		assertThat(summary.dbWrites()).isEqualTo(Map.of("postgresql", List.of(2e-05)));
		assertThat(summary.calls()).containsExactly(Map.entry(new Call("api", "postgresql"), 1),
				Map.entry(new Call("api", "inv"), 1), Map.entry(new Call("api", "redis"), 1));
		assertThat(summary.roots()).containsExactly(1e-4);
		assertThat(summary.cpu()).isEqualTo(Map.of("api", List.of(new CpuSample(5.0, 4e-05))));
		assertThat(List.of(summary.startNs(), summary.endNs())).containsExactly(0L, 100L);
		assertThat(summary.spans()).isEqualTo(7);
		assertThat(summary.parentLoops()).isZero();
	}

	@Test
	void summarize_parentLinksThatLoop_areCountedAndEndTheWalk() {
		TraceSummary summary = TraceSummaries
			.summarize(List.of(span("c CLIENT api x 0 10", "{}"), span("x INTERNAL api c 0 20", "{}")), null);

		assertThat(summary.parentLoops()).isEqualTo(1);
		assertThat(summary.roots()).isEmpty();
	}

	@Test
	void summarize_cpuTimeThatIsNoUsableNumber_isLeftOut() {
		List<Span> spans = List.of(span("a SERVER api - 0 100", "{\"cpu\": true}"),
				span("b SERVER api - 0 100", "{\"cpu\": \"2.5\"}"), span("c SERVER api - 0 100", "{\"cpu\": -1}"),
				span("d SERVER api - 0 100", "{\"cpu\": [1]}"));

		assertThat(TraceSummaries.summarize(spans, new CpuTime("cpu", 1000.0)).cpu())
			.isEqualTo(Map.of("api", List.of(new CpuSample(2500.0, 1e-4))));
	}

	@Test
	void summarize_noSpans_isEmpty() {
		TraceSummary summary = TraceSummaries.summarize(List.of(), null);

		assertThat(summary.spans()).isZero();
		assertThat(summary.services()).isEmpty();
		assertThat(summary.endNs()).isZero();
	}

	@Test
	void covered_overlappingIntervals_countOnceWithinTheSpan() {
		List<long[]> intervals = List.of(new long[] { 5, 15 }, new long[] { 10, 20 }, new long[] { 30, 40 },
				new long[] { 0, 2 });

		assertThat(TraceSummaries.covered(intervals, 3, 35)).isEqualTo(20);
	}

	/**
	 * A span from its id, kind, service, parent ({@code -} for none), start and end, and
	 * its attributes.
	 */
	private static Span span(String spec, String attrs) {
		String[] parts = spec.split(" ", -1);
		return new Span("t", parts[0], "-".equals(parts[3]) ? null : parts[3], parts[2], parts[0], parts[1],
				Long.parseLong(parts[4]), Long.parseLong(parts[5]), (ObjectNode) JSON.readTree(attrs));
	}

}
