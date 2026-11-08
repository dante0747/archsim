package io.archsim.engine.components.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.ReadFrom;
import io.archsim.model.component.database.Replication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database's behaviour in effect, as the Python engine 0.16.0 resolves its settings: as
 * set, else the technology's default, else the engine's.
 */
class DatabaseSettingsTests {

	@ParameterizedTest
	@CsvSource(textBlock = """
			one,    1
			quorum, 2
			all,    3
			""")
	void copiesWaitedFor_threeCopies_followsTheConsistencyLevel(String consistency, int copies) {
		DatabaseSettings settings = settings(
				"{\"replicationFactor\": 3, \"consistency\": \"%s\"}".formatted(consistency));

		assertThat(settings.copiesWaitedFor()).isEqualTo(copies);
	}

	@Test
	void replication_withoutReadReplicas_isNone() {
		assertThat(settings("{}").replication()).isEqualTo(Replication.NONE);
	}

	@Test
	void replication_withReadReplicas_isTheTechnologys() {
		assertThat(settings("{\"readReplicas\": 1}").replication()).isEqualTo(Replication.ASYNC);
	}

	@Test
	void bufferHitRatio_aMemorySizeWithoutAWorkingSet_isEmpty() {
		assertThat(settings("{\"memoryGib\": 4}").bufferHitRatio()).isEmpty();
	}

	@Test
	void bufferHitRatio_aWorkingSetLargerThanTheCache_isTheShareItHolds() {
		assertThat(settings("{\"memoryGib\": 4, \"workingSetGib\": 10}").bufferHitRatio()).hasValue(0.3);
	}

	@Test
	void bufferHitRatio_aCacheLargerThanTheWorkingSet_isOne() {
		assertThat(settings("{\"memoryGib\": 100, \"workingSetGib\": 1}").bufferHitRatio()).hasValue(1.0);
	}

	@Test
	void scatterShare_aSearchStoreWithOneShard_isNone() {
		assertThat(settings("{\"technology\": \"elasticsearch\"}").scatterShare()).isZero();
	}

	@Test
	void scatterShare_aSearchStoreWithShards_isTheTechnologys() {
		assertThat(settings("{\"technology\": \"elasticsearch\", \"shards\": 3}").scatterShare()).isEqualTo(1.0);
	}

	@Test
	void routerOverheadMs_withoutADefault_isNone() {
		assertThat(settings("{}").routerOverheadMs()).isZero();
	}

	@Test
	void routerOverheadMs_ofMongoDb_isItsRouters() {
		assertThat(settings("{\"technology\": \"mongodb\"}").routerOverheadMs()).isEqualTo(0.5);
	}

	@Test
	void sstablesPerRead_withoutADefault_isOne() {
		assertThat(settings("{}").sstablesPerRead()).isEqualTo(1.0);
	}

	@Test
	void sstablesPerRead_ofCassandra_isTheTechnologys() {
		assertThat(settings("{\"technology\": \"cassandra\"}").sstablesPerRead()).isEqualTo(2.0);
	}

	@Test
	void readTickets_ofMongoDb_areItsTickets() {
		assertThat(settings("{\"technology\": \"mongodb\"}").readTickets()).hasValue(128);
	}

	@Test
	void readTickets_withoutADefault_areEmpty() {
		assertThat(settings("{}").readTickets()).isEmpty();
	}

	@Test
	void readersWaitForWriters_ofSqlServer_isTrue() {
		assertThat(settings("{\"technology\": \"sqlserver\"}").readersWaitForWriters()).isTrue();
	}

	@Test
	void locksRows_ofCassandra_isFalse() {
		assertThat(settings("{\"technology\": \"cassandra\"}").locksRows()).isFalse();
	}

	@Test
	void readFrom_ofMongoDb_isThePrimary() {
		assertThat(settings("{\"technology\": \"mongodb\"}").readFrom()).isEqualTo(ReadFrom.PRIMARY);
	}

	@Test
	void readFrom_ofPostgresql_isEveryInstance() {
		assertThat(settings("{}").readFrom()).isEqualTo(ReadFrom.ALL);
	}

	private static DatabaseSettings settings(String json) {
		return new DatabaseSettings(
				RecordCodec.read(JsonMapper.builder().build().readTree(json), DatabaseConfig.class));
	}

}
