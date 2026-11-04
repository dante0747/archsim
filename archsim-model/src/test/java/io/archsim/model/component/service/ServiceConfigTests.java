package io.archsim.model.component.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

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

	private static ServiceConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), ServiceConfig.class);
	}

}
