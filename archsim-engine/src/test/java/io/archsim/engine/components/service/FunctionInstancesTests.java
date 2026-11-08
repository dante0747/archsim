package io.archsim.engine.components.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.components.service.FunctionInstances.Instance;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.service.ServiceConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A serverless function's instances: the one that finished last is reused first, one idle
 * longer than the keep-warm time is reclaimed, and new ones stay within the concurrency
 * limit and the scale-out rate.
 */
class FunctionInstancesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Simulation simulation = new Simulation();

	private final Timeline timeline = new Timeline(10_000, 0, 10_000);

	@Test
	void take_noIdleInstance_startsAColdOne() {
		FunctionInstances instances = instances("{}");

		assertThat(instances.take(5)).contains(new Instance(5, true));
		assertThat(instances.alive()).isOne();
	}

	@Test
	void take_twoIdleInstances_reusesTheOneThatFinishedLast() {
		FunctionInstances instances = instances("{}");
		Instance first = instances.take(1).orElseThrow();
		Instance second = instances.take(2).orElseThrow();
		instances.release(second, 3);
		instances.release(first, 4);

		assertThat(instances.take(5)).contains(new Instance(1, false));
	}

	@Test
	void take_atTheConcurrencyLimit_isThrottled() {
		FunctionInstances instances = instances("{\"concurrencyLimit\": 1}");
		instances.take(1);

		assertThat(instances.take(2)).isEmpty();
	}

	@Test
	void take_beyondTheScaleOutRate_isThrottled() {
		// one instance per 10 seconds, the first at once
		FunctionInstances instances = instances("{\"scaleOutRate\": 1}");
		instances.take(1);

		assertThat(instances.take(2)).isEmpty();
	}

	@Test
	void take_anInstanceIdleLongerThanTheKeepWarmTime_startsANewOne() {
		FunctionInstances instances = instances("{\"keepWarmS\": 1}");
		instances.release(instances.take(10).orElseThrow(), 20);

		assertThat(instances.take(1_021)).contains(new Instance(1_021, true));
		assertThat(instances.alive()).isOne();
	}

	@Test
	void take_anInstanceIdleForTheKeepWarmTime_reusesIt() {
		FunctionInstances instances = instances("{\"keepWarmS\": 1}");
		instances.release(instances.take(10).orElseThrow(), 20);

		assertThat(instances.take(1_020)).contains(new Instance(10, false));
	}

	@Test
	void startReclaiming_anInstanceStillWarmAtACheck_keepsIt() {
		// checked every second
		FunctionInstances instances = instances("{\"keepWarmS\": 1}");
		instances.release(instances.take(10).orElseThrow(), 20);
		instances.startReclaiming();

		this.simulation.runUntil(1_500, 1, () -> {
		});

		assertThat(instances.alive()).isOne();
	}

	@Test
	void startReclaiming_anInstanceIdleTooLongAtACheck_reclaimsIt() {
		FunctionInstances instances = instances("{\"keepWarmS\": 1}");
		instances.release(instances.take(10).orElseThrow(), 20);
		instances.startReclaiming();

		this.simulation.runUntil(2_500, 1, () -> {
		});

		assertThat(instances.alive()).isZero();
	}

	@Test
	void startReclaiming_aLongKeepWarmTime_keepsTheInstanceUntilA30SecondCheck() {
		// idle too long from 600,020 ms; checked every 30 seconds
		FunctionInstances instances = instances("{\"keepWarmS\": 600}");
		instances.release(instances.take(10).orElseThrow(), 20);
		instances.startReclaiming();

		this.simulation.runUntil(629_000, 1, () -> {
		});

		assertThat(instances.alive()).isOne();
	}

	@Test
	void startReclaiming_aLongKeepWarmTime_reclaimsAtTheNext30SecondCheck() {
		FunctionInstances instances = instances("{\"keepWarmS\": 600}");
		instances.release(instances.take(10).orElseThrow(), 20);
		instances.startReclaiming();

		this.simulation.runUntil(631_000, 1, () -> {
		});

		assertThat(instances.alive()).isZero();
	}

	@Test
	void peak_twoInstancesAtOnce_isTwo() {
		FunctionInstances instances = instances("{}");
		instances.release(instances.take(10).orElseThrow(), 20);
		instances.take(30);
		instances.take(40);
		instances.finish(10_000);

		assertThat(instances.peak()).isEqualTo(2);
	}

	private FunctionInstances instances(String settings) {
		ObjectNode config = (ObjectNode) JSON.readTree(settings);
		config.put("platform", "serverless");
		return new FunctionInstances(this.simulation, this.timeline, RecordCodec.read(config, ServiceConfig.class));
	}

}
