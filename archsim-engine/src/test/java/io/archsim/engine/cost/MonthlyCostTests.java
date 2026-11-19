package io.archsim.engine.cost;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.report.RunReport;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/**
 * A month is the hours the workload says it runs at its peak, billed at the peak, and the
 * rest of its 730 hours, billed at the base load (ADR-0074).
 */
class MonthlyCostTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BURST = "{\"kind\": \"burst\", \"burstRps\": 1000, \"startS\": 10, \"durationS\": 10}";

	private static final String CONSTANT = "{\"kind\": \"constant\"}";

	private final DesignAnalyzer analyzer = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped());

	private final CostEstimator costs = new CostEstimator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void estimate_noPeakHours_billsTheWholeMonthAtTheBase() {
		CostEstimate cost = estimate(autoscaled(BURST, 0));

		assertThat(cost.monthly()).isEqualTo(730.0);
	}

	@Test
	void estimate_somePeakHours_billsThemAtThePeakAndTheRestAtTheBase() {
		CostEstimate cost = estimate(autoscaled(BURST, 24));

		// 2 replicas at $0.50 for 706 hours, and 17 for 24
		assertThat(cost.monthly()).isCloseTo(706 * 1.0 + 24 * 8.5, within(1e-9));
		assertThat(line(cost, "api").monthly()).isCloseTo(910.0, within(1e-9));
		assertThat(line(cost, "api").instances()).isEqualTo(2.0);
	}

	@Test
	void estimate_somePeakHours_sayHowManyAPartRunsAtThePeakAndForHowLong() {
		CostEstimate cost = estimate(autoscaled(BURST, 24));

		assertThat(line(cost, "api").basis()).isEqualTo(
				"2 replicas at the base load (autoscaling 2–20) and 17 replicas at the peak for 24 hours a month");
	}

	@Test
	void estimate_onePeakHour_saysHourInTheSingular() {
		CostEstimate cost = estimate(autoscaled(BURST, 1));

		assertThat(line(cost, "api").basis()).endsWith("17 replicas at the peak for 1 hour a month");
	}

	@Test
	void estimate_somePeakHours_areWhatTheCostBills() {
		CostEstimate cost = estimate(autoscaled(BURST, 24));

		assertThat(cost.peakHours()).isEqualTo(24.0);
	}

	@Test
	void estimate_peakHoursOnAConstantPattern_billNoHourAtAPeakItDoesNotHave() {
		CostEstimate cost = estimate(autoscaled(CONSTANT, 24));

		assertThat(cost.monthly()).isEqualTo(estimate(autoscaled(CONSTANT, 0)).monthly());
		assertThat(cost.peakHours()).isZero();
	}

	@Test
	void estimate_somePeakHours_putTheDearestLineFirstForTheMonth() {
		// 3 fixed replicas cost $1.50 an hour, the autoscaled service $1.00 at the
		// base and $8.50 at the peak: 200 peak hours make it the dearer of the two
		CostEstimate cost = estimate(autoscaledBesideFixed(200));

		assertThat(cost.byNode()).extracting(CostLine::id).startsWith("api", "fixed");
	}

	@Test
	void estimate_somePeakHours_leaveAFixedPartAsItIs() {
		CostEstimate cost = estimate(autoscaledBesideFixed(200));

		assertThat(line(cost, "fixed").monthly()).isCloseTo(730 * 1.5, within(1e-9));
		assertThat(line(cost, "fixed").basis()).isEqualTo("3 replicas");
	}

	@Test
	void estimate_somePeakHours_sayWhatTheMonthIsBeforeARun() {
		CostEstimate cost = estimate(calling(30));

		assertThat(cost.notes()).contains("Per-request prices and cost per request are at the steady base load of "
				+ "100.0 req/s for 700 hours of a month and at the peak of 1000.0 req/s for 30 hours: the static load "
				+ "estimate at each rate (run a simulation to measure it).");
	}

	@Test
	void estimateAfterRun_somePeakHours_billPerRequestPartsAtThePeaksRequests() {
		CostEstimate cost = afterRun(30);

		// $0.72 an hour of calls at the base for 700 hours, and $7.20 at the peak for 30
		assertThat(line(cost, "payments").monthly()).isCloseTo(700 * 0.72 + 30 * 7.2, within(1e-9));
		assertThat(line(cost, "payments").items()).singleElement()
			.satisfies((item) -> assertThat(item.quantity()).isCloseTo(360e6, within(1e-3)));
	}

	@Test
	void estimateAfterRun_somePeakHours_countTheRequestsOfBothLoadsInTheCostPerMillion() {
		CostEstimate cost = afterRun(30);

		// 100 successful requests a second for 700 hours and 1,000 for 30: 360 million
		assertThat(cost.perMillionRequests()).isCloseTo(cost.monthly() / 360, within(1e-9));
	}

	@Test
	void estimateAfterRun_somePeakHours_sayWhatTheMonthIs() {
		CostEstimate cost = afterRun(30);

		assertThat(cost.notes()).contains("Per-request prices and cost per request are at the steady base load of "
				+ "100.0 req/s for 700 hours of a month and at the peak of 1000.0 req/s for 30 hours: each part's rate "
				+ "measured while the run's offered load was at each of them, scaled to it exactly, so fan-out, cache "
				+ "misses and retries are kept.");
	}

	@Test
	void estimateAfterRun_somePeakHours_sayAPerRequestPartBillsThem() {
		CostEstimate cost = afterRun(30);

		assertThat(line(cost, "payments").basis()).endsWith("; at the peak for 30 hours a month");
	}

	@Test
	void of_surplusCredits_areTheBasesForItsHoursAndThePeaksForTheirs() {
		CostLine base = pricedLine("db", 1.0, 10.0, null);
		CostLine peak = pricedLine("db", 1.0, 30.0, null);

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		// $10 a month of credits at the base for 657 hours, $30 at the peak for 73
		assertThat(cost.byNode().getFirst().creditsMonthly()).isCloseTo(9.0 + 3.0, within(1e-9));
	}

	@Test
	void of_aLoadBalancerWhoseCapacityUnitsGrowAtThePeak_pricesTheMonthsHour() {
		CostLine base = hourly("alb", 1, 0.03, "1 load balancer");
		CostLine peak = hourly("alb", 1, 0.05, "1 load balancer");

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		// $21.90 a month at the base for 657 hours and $36.50 at the peak for 73: $23.36
		assertThat(cost.byNode().getFirst().unitPrice()).isCloseTo(23.36 / 730, within(1e-12));
	}

	@Test
	void of_aPerRequestLineDearerAtThePeak_pricesAMillionAtTheMonthsRequests() {
		CostLine base = perMillion(2.0, 100);
		CostLine peak = perMillion(4.0, 1000);

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		// $200 for 100 million at the base, $4,000 for 1,000 million at
		// the peak: $580 for 90 + 100 million
		assertThat(cost.byNode().getFirst().unitPrice()).isCloseTo(580.0 / 190, within(1e-12));
	}

	@Test
	void of_aCountFollowedByANote_putsThePeaksCountBeforeTheNote() {
		String note = "; + its managed control plane at $0.1/h";
		CostLine base = hourly("k8s", 3, 0.2, "3 nodes at the base load (autoscaling 1–10)" + note);
		CostLine peak = hourly("k8s", 6, 0.2, "6 nodes at the peak (autoscaling 1–10)" + note);

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		assertThat(cost.byNode().getFirst().basis()).isEqualTo(
				"3 nodes at the base load (autoscaling 1–10) and 6 nodes at the peak for 73 hours a month" + note);
	}

	@Test
	void of_podsOnImpliedNodes_putThePeaksCountBeforeTheNodes() {
		String nodes = " on implied c6g.12xlarge nodes (no cluster in the design): 0.05 of a node each";
		CostLine base = hourly("api", 3, 0.0756, "3 pods at the base load (autoscaling 3–14)" + nodes);
		CostLine peak = hourly("api", 12, 0.0756, "12 pods at the peak (autoscaling 3–14)" + nodes);

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		assertThat(cost.byNode().getFirst().basis()).isEqualTo(
				"3 pods at the base load (autoscaling 3–14) and 12 pods at the peak for 73 hours a month" + nodes);
	}

	@Test
	void of_anAverageLeftUnscaledAtThePeak_saysItIsAtThePeak() {
		CostLine base = hourly("fn", 1.2, 1.0, "avg 1.2 active instances at the base load");
		CostLine peak = hourly("fn", 3.4, 1.0, "avg 3.4 active instances");

		CostEstimate cost = MonthlyCost.of(priced(base), priced(peak), 73);

		assertThat(cost.byNode().getFirst().basis()).isEqualTo("avg 1.2 active instances at the base load and "
				+ "avg 3.4 active instances at the peak for 73 hours a month");
	}

	@Test
	void of_linesThatPriceOtherItemsAtEachLoad_areRefused() {
		CostLine base = pricedLine("queue", 0.0, null, List.of(CostItem.perMillion("requests", 1e6, 0.4, "request")));
		CostLine peak = pricedLine("queue", 0.0, null, List.of(CostItem.perMillion("messages", 1e6, 0.4, "message")));

		assertThatIllegalStateException().isThrownBy(() -> MonthlyCost.of(priced(base), priced(peak), 24))
			.withMessageContaining("queue");
	}

	// a part priced at $1 an instance-hour, with the credits and items given
	private static CostLine pricedLine(String id, double instances, @Nullable Double credits,
			@Nullable List<CostItem> items) {
		return new CostLine(id, id, "service", null, instances, null, 1.0, PriceBasis.SET, CostUnit.HOUR,
				730.0 + ((credits != null) ? credits : 0.0), "1 replica", credits, null, null, null, items);
	}

	// a part priced per instance-hour, its basis as given
	private static CostLine hourly(String id, double instances, double unitPrice, String basis) {
		return new CostLine(id, id, "service", null, instances, null, unitPrice, PriceBasis.SNAPSHOT, CostUnit.HOUR,
				instances * unitPrice * 730, basis, null, null, null, null, null);
	}

	// a part priced per million requests
	private static CostLine perMillion(double unitPrice, double millions) {
		return new CostLine("fn", "fn", "service", null, 0, null, unitPrice, PriceBasis.SNAPSHOT, CostUnit.MILLION,
				unitPrice * millions, "invocations", null, null, null, null, null);
	}

	private static PricedLoad priced(CostLine line) {
		return new PricedLoad(List.of(line), line.monthly(), null, List.of(), List.of(), true,
				JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode());
	}

	private CostEstimate estimate(String design) {
		return this.analyzer.analyze(JSON.readTree(design), null).cost();
	}

	// two fixed replicas and a third-party API, in a run of the base for two seconds and
	// the peak for two more
	private CostEstimate afterRun(int peakHours) {
		ArchitectureDocument design = DesignDocuments.parse(JSON.readTree(calling(peakHours)));
		String api = node("api", "100, 100, 1000, 1000");
		String payments = node("payments", "100, 100, 1000, 1000");
		return this.costs.estimateAfterRun(design, Map.of(), BusyCores.none(),
				report(api + ", " + payments, 100, 100, 1000, 1000));
	}

	private static CostLine line(CostEstimate cost, String id) {
		return cost.byNode().stream().filter((line) -> line.id().equals(id)).findFirst().orElseThrow();
	}

	/**
	 * A service whose replica holds a request for 100 ms on one of its 10 workers, 5 ms
	 * of it on its half vCPU, at $0.50 an hour: 100 req/s keep one replica busy.
	 * @param pattern the workload's pattern, from 100 req/s
	 * @param peakHours the hours a month at the peak
	 * @return the design
	 */
	private static String autoscaled(String pattern, int peakHours) {
		return """
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "month"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 5, "autoscaling": true,
				     "minReplicas": 2, "maxReplicas": 20, "targetUtilization": 0.6, "workersPerReplica": 10,
				     "serviceTimeMs": {"kind": "constant", "value": 100}, "cpuCores": 0.5, "cpuShare": 0.05,
				     "costPerHour": 0.5}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 100, "pattern": %s, "peakHoursPerMonth": %s}}""".formatted(pattern, peakHours);
	}

	/**
	 * The autoscaled service of {@link #autoscaled} under a burst, which calls a service
	 * of 3 fixed replicas at $0.50 an hour each.
	 * @param peakHours the hours a month at the peak
	 * @return the design
	 */
	private static String autoscaledBesideFixed(int peakHours) {
		return """
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "month"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 5, "autoscaling": true,
				     "minReplicas": 2, "maxReplicas": 20, "targetUtilization": 0.6, "workersPerReplica": 10,
				     "serviceTimeMs": {"kind": "constant", "value": 100}, "cpuCores": 0.5, "cpuShare": 0.05,
				     "costPerHour": 0.5}},
				    {"id": "fixed", "type": "service", "config": {"replicas": 3, "costPerHour": 0.5,
				     "serviceTimeMs": {"kind": "constant", "value": 1}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				            {"id": "api-fixed", "source": "api", "target": "fixed"}]},
				 "workload": {"rps": 100, "pattern": %s, "peakHoursPerMonth": %s}}""".formatted(BURST, peakHours);
	}

	/**
	 * Two replicas of a service at $0.50 an hour each, which call a third-party API at $2
	 * a million calls.
	 * @param peakHours the hours a month at the peak
	 * @return the design
	 */
	private static String calling(int peakHours) {
		return """
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "calls"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 2, "costPerHour": 0.5}},
				    {"id": "payments", "type": "external_api", "config": {"costPerMillionRequests": 2}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				            {"id": "api-payments", "source": "api", "target": "payments"}]},
				 "workload": {"rps": 100, "pattern": %s, "peakHoursPerMonth": %s}}""".formatted(BURST, peakHours);
	}

	private static String node(String id, String arrivals) {
		return """
				{"id": "%s", "type": "service", "category": "compute", "label": "%s",
				 "summary": {"arrivalRps": 550}, "timeseries": {"arrivalRps": [%s]}}""".formatted(id, id, arrivals);
	}

	private static RunReport report(String nodes, double... offered) {
		double mean = Arrays.stream(offered).average().orElseThrow();
		return RunReport.read(JSON.readTree("""
				{"summary": {"latencyMs": {}, "failureBreakdown": [], "offeredRps": %s, "throughputRps": %s},
				 "nodes": [{"id": "clients", "type": "traffic_source", "category": "traffic", "label": "Clients",
				            "summary": {}, "timeseries": {}}, %s],
				 "findings": [], "provenance": {"resultDigest": "f"},
				 "timeseries": {"bucketS": 1.0, "t": [0, 1, 2, 3], "offeredRps": %s},
				 "window": {"warmupS": 0.0, "durationS": 4.0, "measuredS": 4.0}}""".formatted(mean, mean, nodes,
				Arrays.toString(offered))));
	}

}
