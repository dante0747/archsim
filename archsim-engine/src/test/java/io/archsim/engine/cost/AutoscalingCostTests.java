package io.archsim.engine.cost;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.report.RunReport;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * An autoscaled part is billed at each load the count its autoscaler settles at there
 * (ADR-0074): a service the replicas that run its work at its target utilization, a
 * cluster the nodes its pods need, each within its bounds. A month is billed at the base
 * load; an hour at the peak is reported beside it.
 */
class AutoscalingCostTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BURST = "{\"kind\": \"burst\", \"burstRps\": 1000, \"startS\": 10, \"durationS\": 10}";

	private static final String VM = "vm";

	private static final String KUBERNETES = "kubernetes";

	private final DesignAnalyzer analyzer = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped());

	private final CostEstimator costs = new CostEstimator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void estimate_anAutoscaledServiceStartedAboveItsMinimum_isBilledItsMinimumAtALightBase() {
		CostEstimate cost = estimate(design(service(VM, true), 50));

		// 50 req/s keep half a replica busy, which one replica runs under its target
		assertThat(line(cost, "api").instances()).isEqualTo(2.0);
		assertThat(line(cost, "api").basis()).isEqualTo("2 replicas at the base load (autoscaling 2–20)");
	}

	@Test
	void estimate_anAutoscaledService_isBilledAtThePeakTheReplicasItsAutoscalerWantsThere() {
		CostEstimate cost = estimate(design(service(VM, true), 100));

		// 1,000 req/s keep 10 replicas busy: 10 ÷ 0.6 = 16.7
		assertThat(line(cost, "api").peakInstances()).isEqualTo(17.0);
	}

	@Test
	void estimate_aFixedService_isBilledItsReplicasAtBothLoads() {
		CostEstimate cost = estimate(design(service(VM, false), 100));

		assertThat(line(cost, "api").instances()).isEqualTo(5.0);
		assertThat(line(cost, "api").peakInstances()).isEqualTo(5.0);
	}

	@Test
	void estimate_hourly_isAnHourAtTheBaseAndAnHourAtThePeak() {
		CostEstimate cost = estimate(design(service(VM, true), 100));

		assertThat(cost.hourly()).isEqualTo(new HourlyCost(1.0, 8.5));
		assertThat(cost.monthly()).isEqualTo(730.0);
	}

	@Test
	void estimate_anAutoscaledCluster_runsTheNodesItsPodsNeedAtEachLoad() {
		CostEstimate cost = estimate(design(service(KUBERNETES, true) + ", " + cluster(true), 100));

		// 2 pods at the base fit one node; 17 at the peak need six
		assertThat(line(cost, "k8s").instances()).isEqualTo(1.0);
		assertThat(line(cost, "k8s").peakInstances()).isEqualTo(6.0);
		assertThat(line(cost, "k8s").basis()).startsWith("1 node at the base load (autoscaling 1–10)");
	}

	@Test
	void estimate_aClusterWithoutItsAutoscaler_isBilledItsNodesAtEachLoad() {
		CostEstimate cost = estimate(design(service(KUBERNETES, true) + ", " + cluster(false), 100));

		assertThat(line(cost, "k8s").instances()).isEqualTo(3.0);
		assertThat(line(cost, "k8s").peakInstances()).isEqualTo(3.0);
	}

	@Test
	void estimateAfterRun_anAutoscaledService_isBilledWhatItsAutoscalerWantsAtEachLoadOfTheRun() {
		ArchitectureDocument design = parse(design(service(VM, true), 100));
		// half of 5 replicas' workers busy at 550 req/s
		String api = node("api", "service", "\"utilization\": 0.5, \"cpuCoreUtilization\": 0.2, \"replicasMean\": 5",
				"\"arrivalRps\": [100, 100, 1000, 1000], \"replicas\": [5, 2, 6, 8]");

		CostEstimate cost = this.costs.estimateAfterRun(design, Map.of(), BusyCores.none(),
				report(api, 100, 100, 1000, 1000));

		// at 100 req/s, 0.45 replicas busy; at 1,000, 4.5 (7.6 at the target)
		assertThat(line(cost, "api").instances()).isEqualTo(2.0);
		assertThat(line(cost, "api").peakInstances()).isEqualTo(8.0);
		assertThat(cost.hourly()).isEqualTo(new HourlyCost(1.0, 4.0));
	}

	@Test
	void estimateAfterRun_aConstantPattern_pricesThePeakHourAsTheBase() {
		ArchitectureDocument design = parse(calling("{\"kind\": \"constant\"}"));
		// the last bucket offers more than the base takes, and fans out twice
		String payments = node("payments", "external_api", "", "\"arrivalRps\": [80, 120, 100, 260]");

		CostEstimate cost = this.costs.estimateAfterRun(design, Map.of(), BusyCores.none(),
				report(api(80, 120, 100, 130) + ", " + payments, 80, 120, 100, 130));

		assertThat(cost.hourly().peak()).isEqualTo(cost.hourly().base());
		assertThat(line(cost, "payments").peakInstances()).isEqualTo(line(cost, "payments").instances());
	}

	@Test
	void estimateAfterRun_aPerRequestPart_isPricedAtThePeaksRequests() {
		ArchitectureDocument design = parse(calling(BURST));
		String payments = node("payments", "external_api", "", "\"arrivalRps\": [100, 100, 1000, 1000]");

		CostEstimate cost = this.costs.estimateAfterRun(design, Map.of(), BusyCores.none(),
				report(api(100, 100, 1000, 1000) + ", " + payments, 100, 100, 1000, 1000));

		// two replicas at $0.50 an hour, and 100 or 1,000 calls a second at $2 a million,
		// which cost $0.72 or $7.20 an hour
		assertThat(cost.hourly().base()).isCloseTo(1.72, within(1e-9));
		assertThat(cost.hourly().peak()).isCloseTo(8.2, within(1e-9));
	}

	@Test
	void estimateAfterRun_aServerlessFunction_isBilledItsAverageInstancesScaledToThePeak() {
		ArchitectureDocument design = parse(design("""
				{"id": "api", "type": "service",
				 "config": {"platform": "serverless", "replicas": 1, "costPerHour": 0.5}}""", 100));
		String function = node("api", "service", "",
				"\"arrivalRps\": [100, 100, 1000, 1000], \"replicas\": [2, 2, 18, 18]");

		CostEstimate cost = this.costs.estimateAfterRun(design, Map.of(), BusyCores.none(),
				report(function, 100, 100, 1000, 1000));

		// 10 instances on average at 550 req/s, scaled to 100 and to 1,000, at least its
		// 1 replica
		assertThat(line(cost, "api").instances()).isCloseTo(10.0 * 100 / 550, within(1e-9));
		assertThat(line(cost, "api").peakInstances()).isCloseTo(10.0 * 1000 / 550, within(1e-9));
	}

	private static ArchitectureDocument parse(String design) {
		return DesignDocuments.parse(JSON.readTree(design));
	}

	/**
	 * Two replicas of a service at $0.50 an hour each, which call a third-party API at $2
	 * a million calls.
	 * @param pattern the workload's traffic pattern, around 100 req/s
	 * @return the design
	 */
	private static String calling(String pattern) {
		return """
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "calls"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 2, "costPerHour": 0.5}},
				    {"id": "payments", "type": "external_api", "config": {"costPerMillionRequests": 2}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				            {"id": "api-payments", "source": "api", "target": "payments"}]},
				 "workload": {"rps": 100, "pattern": %s}}""".formatted(pattern);
	}

	private static String api(double... arrivals) {
		return node("api", "service", "", "\"arrivalRps\": " + Arrays.toString(arrivals));
	}

	private static String node(String id, String type, String summary, String series) {
		return """
				{"id": "%s", "type": "%s", "category": "compute", "label": "%s",
				 "summary": {"arrivalRps": 550%s}, "timeseries": {%s}}""".formatted(id, type, id,
				summary.isEmpty() ? "" : ", " + summary, series);
	}

	/**
	 * A run of four one-second buckets.
	 * @param nodes its parts but the clients, as JSON
	 * @param offered the rate offered in each bucket
	 * @return the run's report
	 */
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

	private CostEstimate estimate(String design) {
		return this.analyzer.analyze(JSON.readTree(design), null).cost();
	}

	private static CostLine line(CostEstimate cost, String id) {
		return cost.byNode().stream().filter((line) -> line.id().equals(id)).findFirst().orElseThrow();
	}

	private static String design(String nodes, int rps) {
		return """
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "autoscaling"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"}, %s],
				                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": %s,
				              "pattern": {"kind": "burst", "burstRps": 1000, "startS": 10, "durationS": 10}}}"""
			.formatted(nodes, rps);
	}

	/**
	 * A service whose replica holds a request for 100 ms on one of its 10 workers, 5 ms
	 * of it on its half vCPU: 100 req/s keep one replica's workers busy, and half its
	 * CPU. It starts at 5 replicas, may run 2 to 20, and costs $0.50 an hour each.
	 * @param platform where it runs
	 * @param autoscaling whether it autoscales
	 * @return the part
	 */
	private static String service(String platform, boolean autoscaling) {
		return """
				{"id": "api", "type": "service", "config": {"platform": "%s", "replicas": 5, "autoscaling": %s,
				 "minReplicas": 2, "maxReplicas": 20, "targetUtilization": 0.6, "workersPerReplica": 10,
				 "serviceTimeMs": {"kind": "constant", "value": 100}, "cpuCores": 0.5, "cpuShare": 0.05,
				 "costPerHour": 0.5}}""".formatted(platform, autoscaling);
	}

	/**
	 * A cluster of 2-vCPU nodes, which hold three of the service's half-vCPU pods each.
	 * @param autoscaler whether its autoscaler is on
	 * @return the part
	 */
	private static String cluster(boolean autoscaler) {
		return """
				{"id": "k8s", "type": "kubernetes_cluster", "config": {"nodes": 3, "nodeVcpu": 2, "nodeMemoryGib": 8,
				 "clusterAutoscaler": %s, "minNodes": 1, "maxNodes": 10, "costPerHour": 0.2}}""".formatted(autoscaler);
	}

}
