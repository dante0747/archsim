package io.archsim.model.component.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.LogNormal;

import static org.assertj.core.api.Assertions.assertThat;

/** What a service's settings mean once its technology's defaults are filled in. */
class ServiceConfigTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void runsEventLoop_aNodeJsService_runsAnEventLoop() {
		assertThat(config("{\"technology\": \"nodejs\"}").runsEventLoop()).isTrue();
	}

	@Test
	void runsEventLoop_aJavaServiceSetToAnEventLoop_runsOne() {
		assertThat(config("{\"technology\": \"java\", \"runtimeModel\": \"event_loop\"}").runsEventLoop()).isTrue();
	}

	@Test
	void runsEventLoop_aGenericService_runsAThreadPool() {
		assertThat(config("{}").runsEventLoop()).isFalse();
	}

	@Test
	void handlersPerReplica_anEventLoop_isItsProcesses() {
		assertThat(config("{\"technology\": \"nodejs\", \"processesPerReplica\": 3, \"workersPerReplica\": 8}")
			.handlersPerReplica()).isEqualTo(3);
	}

	@Test
	void handlersPerReplica_aThreadPool_isItsWorkers() {
		assertThat(config("{\"processesPerReplica\": 3, \"workersPerReplica\": 8}").handlersPerReplica()).isEqualTo(8);
	}

	@Test
	void holdsHandlerThroughCalls_aThreadPoolThatBlocks_holdsIt() {
		assertThat(config("{}").holdsHandlerThroughCalls()).isTrue();
	}

	@Test
	void holdsHandlerThroughCalls_anEventLoop_neverHoldsIt() {
		assertThat(config("{\"technology\": \"nodejs\"}").holdsHandlerThroughCalls()).isFalse();
	}

	@Test
	void holdsHandlerThroughCalls_aThreadPoolThatDoesNotBlock_letsItGo() {
		assertThat(config("{\"blockingDownstream\": false}").holdsHandlerThroughCalls()).isFalse();
	}

	@Test
	void gcPauses_aJavaService_areItsRuntimes() {
		assertThat(config("{\"technology\": \"java\"}").gcPauses()).contains(new GcPauses(12.0, 2000));
	}

	@Test
	void gcPauses_aJavaServiceWithoutPauses_haveNone() {
		assertThat(config("{\"technology\": \"java\", \"gcPauseMs\": 0}").gcPauses()).isEmpty();
	}

	@Test
	void gcPauses_aGenericServiceThatSetsThem_areItsOwn() {
		assertThat(config("{\"gcPauseMs\": 5, \"gcEveryRequests\": 300}").gcPauses()).contains(new GcPauses(5.0, 300));
	}

	@Test
	void warmup_aJavaService_isItsJits() {
		assertThat(config("{\"technology\": \"java\"}").warmup()).contains(new Warmup(2.5, 45_000.0));
	}

	@Test
	void warmup_aGoService_hasNone() {
		assertThat(config("{\"technology\": \"go\"}").warmup()).isEmpty();
	}

	@Test
	void warmup_aGenericServiceThatSetsIt_isItsOwnInMilliseconds() {
		assertThat(config("{\"warmupFactor\": 2, \"warmupS\": 10}").warmup()).contains(new Warmup(2.0, 10_000.0));
	}

	@Test
	void cpuFraction_aServiceWithACpuSize_isItsCpuShare() {
		assertThat(config("{\"cpuCores\": 2, \"cpuShare\": 0.4}").cpuFraction()).isEqualTo(0.4);
	}

	@Test
	void cpuFraction_aServiceWithoutACpuSize_isAllOfIt() {
		assertThat(config("{\"cpuShare\": 0.4}").cpuFraction()).isEqualTo(1.0);
	}

	@Test
	void coldStartInEffect_setOnThePart_isWhatIsSet() {
		assertThat(config("{\"coldStartMs\": {\"kind\": \"constant\", \"value\": 50}}").coldStartInEffect())
			.isEqualTo(new Constant(50));
	}

	@Test
	void coldStartInEffect_aJvmRuntime_isItsDefault() {
		assertThat(config("{\"technology\": \"java\"}").coldStartInEffect()).isEqualTo(new LogNormal(1200, 3500));
	}

	@Test
	void initialReplicas_fixedReplicas_isTheReplicasSet() {
		assertThat(config("{\"replicas\": 5}").initialReplicas()).isEqualTo(5);
	}

	@Test
	void initialReplicas_anAutoscalingServiceSetBelowItsBounds_isItsMinimum() {
		assertThat(config("{\"autoscaling\": true, \"replicas\": 1, \"minReplicas\": 3, \"maxReplicas\": 6}")
			.initialReplicas()).isEqualTo(3);
	}

	@Test
	void initialReplicas_anAutoscalingServiceSetAboveItsBounds_isItsMaximum() {
		assertThat(config("{\"autoscaling\": true, \"replicas\": 9, \"minReplicas\": 3, \"maxReplicas\": 6}")
			.initialReplicas()).isEqualTo(6);
	}

	private static ServiceConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), ServiceConfig.class);
	}

}
