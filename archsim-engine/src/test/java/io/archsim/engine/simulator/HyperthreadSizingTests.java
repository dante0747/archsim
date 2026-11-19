package io.archsim.engine.simulator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.sizing.Sizing;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A part that sets its sibling speed is auto-sized for its hyperthreads, so the size the
 * engine chooses runs the peak it was sized for. Two things are measured of a run: the
 * share of the part's vCPUs that are busy, and its work as a share of the CPU capacity
 * its report gives it, every vCPU busy at the sibling speed. The sizing keeps the second
 * at 60%, and at up to 65% at the edge of a size; the first then stays below it at every
 * sibling speed from 0.3 to 0.8. A part that keeps the one vCPU of whole cores, whose
 * memory needs a larger shape, gets a shape that covers its need on pairs.
 */
@TestInstance(Lifecycle.PER_CLASS)
class HyperthreadSizingTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The CPU work of a request, at full speed. */
	private static final double CPU_MS = 10.0;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private final Map<List<Object>, JsonNode> reports = new ConcurrentHashMap<>();

	/**
	 * 2.4 cores' work at a sibling speed of 0.6. On whole cores that is 4 vCPU at the 60%
	 * target; but four such hyperthreads do 2.4 cores' work only with every one of them
	 * busy, and eight do it at half of what they can do.
	 */
	private final JsonNode betweenSizes = report(0.6, 240);

	/**
	 * 2.6 cores' work at a sibling speed of 0.3. Were each vCPU counted as half a core,
	 * what one busy on each core does, it would be at the edge of 8 vCPU, which do 2.4
	 * cores' work with all of them busy, and a run under load would get there and stay
	 * behind; counted as its sibling speed, it gets 16.
	 */
	private final JsonNode onTheSlowestSiblings = report(0.3, 260);

	/**
	 * 0.65 cores' work at a sibling speed of 0.3, which 1 vCPU does on whole cores, with
	 * memory enough for the cheapest shape that holds it to have 2 vCPU: the part would
	 * run them as hyperthreads, which with both busy do 0.6 of a core's work.
	 */
	private final JsonNode onALargerShape = report(0.3, 65, "\"memoryBaselineMb\": 7000, ");

	@Test
	void run_anAutoSizedServiceOnHyperthreads_getsTheVcpuItsHyperthreadsNeed() {
		assertThat(cpuCores(this.betweenSizes)).isEqualTo(8.0);
	}

	@Test
	void run_anAutoSizedServiceOnHyperthreads_keepsItsCpuWithinTheSizingTarget() {
		assertThat(busyShare(this.betweenSizes)).isLessThanOrEqualTo(Sizing.TARGET_CPU);
	}

	@Test
	void run_anAutoSizedServiceOnHyperthreads_isNotShortOfCpuInItsReview() {
		assertThat(cpuFindings(this.betweenSizes)).isEmpty();
	}

	@Test
	void run_anAutoSizedServiceOnSiblingsAtThreeTenthsOfTheirSpeed_getsVcpuThatDoItsWorkWithAllOfThemBusy() {
		assertThat(cpuCores(this.onTheSlowestSiblings)).isEqualTo(16.0);
	}

	@Test
	void run_anAutoSizedServiceOnSiblingsAtThreeTenthsOfTheirSpeed_refusesNoRequest() {
		assertThat(refused(this.onTheSlowestSiblings)).isZero();
	}

	@Test
	void run_anAutoSizedServiceOnSiblingsAtThreeTenthsOfTheirSpeed_isNotShortOfCpuInItsReview() {
		assertThat(cpuFindings(this.onTheSlowestSiblings)).isEmpty();
	}

	@Test
	void run_aServiceOfOneVcpuWhoseMemoryNeedsALargerShapeOnSiblingsAtThreeTenthsOfTheirSpeed_getsThePairsNeed() {
		// a shape of 2 vCPU holds its memory, but its two vCPUs are pairs, which
		// with both busy do less than the one core: the shape must cover the need
		// on pairs, 4
		assertThat(cpuCores(this.onALargerShape)).isEqualTo(4.0);
	}

	@Test
	void run_aServiceOfOneVcpuWhoseMemoryNeedsALargerShapeOnSiblingsAtThreeTenthsOfTheirSpeed_refusesNoRequest() {
		assertThat(refused(this.onALargerShape)).isZero();
	}

	@Test
	void run_aServiceOfOneVcpuWhoseMemoryNeedsALargerShapeOnSiblingsAtThreeTenthsOfTheirSpeed_isNotShortOfCpu() {
		assertThat(cpuFindings(this.onALargerShape)).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_getsThatSize(Edge edge) {
		assertThat(cpuCores(report(edge))).isEqualTo(edge.vcpus());
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_hasTheWorkOf65PercentOfItsCpuCapacity(Edge edge) {
		assertThat(workShare(report(edge))).isCloseTo(Sizing.CPU_STEP_SLACK, within(0.02));
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_refusesNoRequest(Edge edge) {
		assertThat(refused(report(edge))).isZero();
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_isNotShortOfCpuInItsReview(Edge edge) {
		assertThat(cpuFindings(report(edge))).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_hasNoMoreOfItsVcpusBusyThanItsWorkIsOfWhatTheyCanDo(Edge edge) {
		// work that starts while more vCPUs are busy runs slower and keeps them busy
		// longer; that does not take the share busy past the share the part was sized at
		assertThat(busyShare(report(edge))).isLessThanOrEqualTo(Sizing.CPU_STEP_SLACK);
	}

	@ParameterizedTest
	@MethodSource("edges")
	void run_aServiceAtTheEdgeOfItsSizeOnHyperthreads_hasTheShareOfItsVcpusBusyMeasured(Edge edge) {
		assertThat(busyShare(report(edge))).isCloseTo(edge.busy(), within(0.01));
	}

	/**
	 * The edges of three sizes for each sibling speed: the smallest past 1 vCPU, 8 and
	 * 16. At a sibling speed of a half or less the smallest is 4: two such vCPUs with
	 * both busy do no more than one core's work, which is what the one vCPU of whole
	 * cores does.
	 * @return the edges
	 */
	static Stream<Edge> edges() {
		return Stream.of(new Edge(0.3, 78, 4.0, 0.28), new Edge(0.3, 156, 8.0, 0.21), new Edge(0.3, 312, 16.0, 0.20),
				new Edge(0.4, 104, 4.0, 0.40), new Edge(0.4, 208, 8.0, 0.29), new Edge(0.4, 416, 16.0, 0.27),
				new Edge(0.5, 130, 4.0, 0.47), new Edge(0.5, 260, 8.0, 0.39), new Edge(0.5, 520, 16.0, 0.34),
				new Edge(0.6, 78, 2.0, 0.60), new Edge(0.6, 312, 8.0, 0.47), new Edge(0.6, 624, 16.0, 0.42),
				new Edge(0.8, 104, 2.0, 0.64), new Edge(0.8, 416, 8.0, 0.59), new Edge(0.8, 832, 16.0, 0.57));
	}

	private JsonNode report(Edge edge) {
		return report(edge.siblingSpeed(), edge.rps());
	}

	private JsonNode report(double siblingSpeed, int rps) {
		return report(siblingSpeed, rps, "");
	}

	// the run's report, of a design simulated once
	private JsonNode report(double siblingSpeed, int rps, String settings) {
		return this.reports.computeIfAbsent(List.of(siblingSpeed, rps, settings),
				(_) -> run(siblingSpeed, rps, settings));
	}

	/**
	 * Clients calling one auto-sized replica with plenty of workers, 10 ms of CPU a
	 * request, on hyperthreads.
	 * @param settings more of the service's settings, each followed by a comma
	 */
	private JsonNode run(double siblingSpeed, int rps, String settings) {
		return this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "hyperthreads"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 32, %s
				      "siblingSpeed": %s, "serviceTimeMs": {"kind": "constant", "value": %s}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": %d, "durationS": 40, "warmupS": 5}}""".formatted(settings, siblingSpeed, CPU_MS,
				rps)), RunRequest.of(null));
	}

	private static double cpuCores(JsonNode report) {
		return report.at("/sizing/nodes/api/specs/cpuCores/value").doubleValue();
	}

	// the share of the service's vCPUs that are busy, over the run
	private static double busyShare(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "api").path("cpuCoreUtilization").doubleValue();
	}

	// the requests that reach the service as a share of the most its CPU serves, every
	// vCPU busy at the sibling speed
	private static double workShare(JsonNode report) {
		JsonNode summary = ReferenceDesigns.summaryOf(report, "api");
		return summary.path("arrivalRps").doubleValue() / summary.path("cpuCapacityRps").doubleValue();
	}

	private static int refused(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "api").path("rejectedTotal").intValue();
	}

	// what the review of the design as simulated says of the service's CPU at the peak
	private static List<String> cpuFindings(JsonNode report) {
		return report.required("review")
			.valueStream()
			.filter((finding) -> finding.path("rule").stringValue().equals("cpu-capacity"))
			.map((finding) -> finding.path("title").stringValue())
			.toList();
	}

	/**
	 * The most work a size takes on hyperthreads: 65% of what its vCPUs can do.
	 *
	 * @param siblingSpeed the speed of a vCPU whose core's other vCPU is busy too
	 * @param rps the requests per second, each 10 ms of CPU
	 * @param vcpus the size
	 * @param busy the share of its vCPUs that are busy in the run
	 */
	record Edge(double siblingSpeed, int rps, double vcpus, double busy) {
	}

}
