package io.archsim.engine.estimate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.NodeLoad;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.database.DatabaseConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How the estimate reads a database's commit: a write waits for the log's flush on its
 * connection, and no core works on it, as a run models it ({@code Operation} and
 * {@code LogFlusher} of the database component).
 */
class PartShapesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double EXACT = 1e-12;

	/** 300 reads and 100 writes a second. */
	private static final NodeLoad LOAD = new NodeLoad(300.0, 100.0);

	/**
	 * Reads of 1 ms and writes of 4 ms, half of each on one of 2 cores, and a log flush
	 * of 6 ms.
	 */
	private static final String FLUSHING = """
			{"technology": "postgresql", "cpuCores": 2, "cpuShare": 0.5, "maxConnections": 100,
			 "readServiceTimeMs": {"kind": "constant", "value": 1},
			 "writeServiceTimeMs": {"kind": "constant", "value": 4},
			 "commitFlushMs": {"kind": "constant", "value": 6}}""";

	@Test
	void of_aDatabaseThatFlushesCommits_countsItsQueriesAloneAsCpu() {
		PartShape shape = shape(FLUSHING);

		assertThat(shape.cpu()).isCloseTo((300 * 1.0 + 100 * 4.0) * 0.5 / 1000 / 2, within(EXACT));
	}

	@Test
	void of_aDatabaseThatFlushesCommits_keepsAWriteWaitingForTheFlush() {
		PartShape shape = shape(FLUSHING);

		assertThat(shape.own(Op.WRITE).mean()).isCloseTo(4.0 + 6.0, within(EXACT));
	}

	@Test
	void of_aDatabaseThatFlushesCommits_leavesAReadItsQuery() {
		PartShape shape = shape(FLUSHING);

		assertThat(shape.own(Op.READ).mean()).isCloseTo(1.0, within(EXACT));
	}

	@Test
	void of_aDatabaseThatDoesNotFlushCommits_leavesAWriteItsQuery() {
		PartShape shape = shape(FLUSHING.replace("\"postgresql\"", "\"postgresql\", \"commitFlush\": \"off\""));

		assertThat(shape.own(Op.WRITE).mean()).isCloseTo(4.0, within(EXACT));
	}

	@Test
	void slowedTo_aDatabaseThatFlushesCommits_slowsAWritesQueryAndNotItsFlush() {
		PartShape slowed = shape(FLUSHING).slowedTo(0.5, 0.5);

		// half of the 4 ms query on a core at half speed, the other half and the flush as
		// they were
		assertThat(slowed.own(Op.WRITE).mean()).isCloseTo(2.0 / 0.5 + 2.0 + 6.0, within(EXACT));
	}

	@Test
	void slowedTo_aDatabaseThatFlushesCommits_keepsItsQueriesAloneOnItsCores() {
		PartShape slowed = shape(FLUSHING).slowedTo(0.5, 0.5);

		assertThat(slowed.cpu()).isCloseTo((300 * 1.0 + 100 * 4.0) * 0.5 / 1000 / 2 / 0.5, within(EXACT));
	}

	private static PartShape shape(String database) {
		DatabaseConfig config = RecordCodec.read((ObjectNode) JSON.readTree(database), DatabaseConfig.class);
		return PartShapes.of(config, LOAD).orElseThrow();
	}

}
