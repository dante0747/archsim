package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The instant estimate of a part on its own hyperthread pairs (ADR-0091): it saturates
 * where its vCPUs fill at their speed then, every vCPU busy at the sibling speed, not at
 * the speed they run at the peak, which is faster while some of them idle; past them its
 * work runs at the sibling speed and waits for a vCPU.
 */
class PairsSaturationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final double TOLERANCE = 1e-9;

	private static final double ON_PAIRS = 0.6;

	private static final double ON_WHOLE_CORES = 1.0;

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	@Test
	void estimate_aServiceOnPairs_saturatesWhereItsVcpusFillAtTheSiblingSpeed() {
		// 4 vCPU × 0.6 of a core each, 10 ms of CPU a request: 240 requests a second
		InstantEstimate estimate = this.estimator.estimate(service(ON_PAIRS, 200));

		assertThat(estimate.saturationRps()).isEqualTo(240.0);
	}

	@Test
	void estimate_aServiceOnPairsWhoseVcpusAreSlowedAtThePeak_saturatesWhereTheyFillAtTheSiblingSpeed() {
		// 2.2 cores' work: 3 of its vCPUs busy, at 0.73 of full speed
		InstantEstimate estimate = this.estimator.estimate(service(ON_PAIRS, 220));

		assertThat(estimate.saturationRps()).isEqualTo(240.0);
	}

	@Test
	void estimate_aServiceOnPairsWhoseVcpusAreSlowedAtThePeak_needsTheShareOfItsCeilingThePeakTakes() {
		EstimatedPart api = EstimatedParts.part(this.estimator.estimate(service(ON_PAIRS, 220)), "api");

		assertThat(api.load()).isCloseTo(220.0 / 240.0, within(1e-4));
	}

	@Test
	void estimate_aServiceOnPairsWhoseVcpusAreSlowedAtThePeak_keepsTheShareOfItsVcpusBusyThen() {
		// what a run's utilization measures: the work at the peak's speed over the vCPUs,
		// 2.2 cores' work at 0.73 of full speed on 4 vCPU
		EstimatedPart api = EstimatedParts.part(this.estimator.estimate(service(ON_PAIRS, 220)), "api");

		assertThat(api.cpuUtilization()).isCloseTo(0.75, within(TOLERANCE));
	}

	@Test
	void estimate_anOverloadedServiceOnPairs_saturatesWhereItsVcpusFillAtTheSiblingSpeed() {
		// 300 requests a second are 3 cores' work, more than the 2.4 its vCPUs do
		InstantEstimate estimate = this.estimator.estimate(service(ON_PAIRS, 300));

		assertThat(estimate.saturationRps()).isEqualTo(240.0);
	}

	@Test
	void estimate_anOverloadedServiceOnPairs_runsItsWorkAtTheSiblingSpeed() {
		// past its vCPUs a request waits for one, which its CPU share past 1 counts
		EstimatedPart api = EstimatedParts.part(this.estimator.estimate(service(ON_PAIRS, 300)), "api");

		assertThat(api.meanServiceMs()).isEqualTo(16.667);
	}

	@Test
	void estimate_aServiceOnTheDefault_saturatesWhereItsVcpusFillAtTheDefaultSpeed() {
		InstantEstimate estimate = this.estimator.estimate(service(null, 200));

		assertThat(estimate.saturationRps()).isEqualTo(240.0);
	}

	@Test
	void estimate_aServiceOnPairsAtALightLoad_saturatesWhereItsVcpusFillAtTheSiblingSpeedToo() {
		// one busy vCPU on each core runs at full speed, but the pairs fill all the same
		InstantEstimate estimate = this.estimator.estimate(service(ON_PAIRS, 100));

		assertThat(estimate.saturationRps()).isEqualTo(240.0);
	}

	@Test
	void estimate_aServiceOnPairs_isBusyFromEightyPercentOfItsCpuCeiling() {
		EstimatedPart api = EstimatedParts.part(this.estimator.estimate(service(ON_PAIRS, 200)), "api");

		assertThat(api.status()).isEqualTo(PartStatus.BUSY);
	}

	@Test
	void estimate_aServiceOnPairsPastItsCpuCeiling_isOverloaded() {
		EstimatedPart api = EstimatedParts.part(this.estimator.estimate(service(ON_PAIRS, 250)), "api");

		assertThat(api.status()).isEqualTo(PartStatus.OVERLOADED);
	}

	@Test
	void estimate_aServiceOnWholeCores_saturatesWhereItsCoresFill() {
		InstantEstimate estimate = this.estimator.estimate(service(ON_WHOLE_CORES, 200));

		assertThat(estimate.saturationRps()).isEqualTo(400.0);
	}

	@Test
	void estimate_aDatabaseOnPairs_saturatesWhereItsVcpusFillAtTheSiblingSpeed() {
		// half of each 10 ms read on a core: 4 vCPU × 0.6 do 480 reads a second, whole
		// cores 800
		InstantEstimate estimate = this.estimator.estimate(resolved("""
				{"id": "db", "type": "database", "config": {"cpuCores": 4, "siblingSpeed": 0.6, "cpuShare": 0.5,
				  "maxConnections": 200, "readServiceTimeMs": {"kind": "constant", "value": 10}}}""", 200));

		assertThat(estimate.saturationRps()).isEqualTo(480.0);
	}

	@Test
	void estimate_aShardedCacheOnPairs_saturatesWhereEveryShardsVcpusFillAtTheSiblingSpeed() {
		// 2 shards × 2 vCPU × 0.6, 1 ms a command: 2,400 commands a second
		InstantEstimate estimate = this.estimator.estimate(resolved("""
				{"id": "cache", "type": "cache", "config": {"technology": "memcached", "shards": 2, "cpuCores": 2,
				  "siblingSpeed": 0.6, "concurrency": 8, "hitRatio": 1,
				  "serviceTimeMs": {"kind": "constant", "value": 1}}}""", 1200));

		assertThat(estimate.saturationRps()).isEqualTo(2400.0);
	}

	/**
	 * Clients calling a service of 4 vCPU, 10 ms of CPU a request, with workers enough
	 * not to limit it.
	 * @param siblingSpeed the service's sibling speed; {@code null} for the default
	 * @param rps the requests per second
	 */
	private static ArchitectureDocument service(@Nullable Double siblingSpeed, int rps) {
		String speed = (siblingSpeed != null) ? ", \"siblingSpeed\": " + siblingSpeed : "";
		return resolved("""
				{"id": "api", "type": "service", "config": {"replicas": 1, "workersPerReplica": 64, "cpuCores": 4%s,
				  "serviceTimeMs": {"kind": "constant", "value": 10}}}""".formatted(speed), rps);
	}

	/**
	 * Clients reading from one part, resolved as a run resolves it.
	 * @param part the part, as JSON
	 * @param rps the requests per second, every one a read
	 */
	private static ArchitectureDocument resolved(String part, int rps) {
		String id = JSON.readTree(part).path("id").asString();
		return RESOLVER.resolve(DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "pairs"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"}, %s],
				  "edges": [{"id": "clients-part", "source": "clients", "target": "%s"}]},
				 "workload": {"rps": %d, "readRatio": 1}}""".formatted(part, id, rps))), null).document();
	}

}
