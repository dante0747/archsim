package io.archsim.model.component.database;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a database's settings mean once its technology's defaults are filled in. Each case
 * is what the Python engine 0.16.0 gives.
 */
class DatabaseConfigTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PROVISIONED = """
			{"technology": "dynamodb", "capacityMode": "provisioned", "readCapacityUnits": 500,
			 "writeCapacityUnits": 200}""";

	@Test
	void flushesCommits_postgresql_flushesThem() {
		assertThat(config("{\"technology\": \"postgresql\"}").flushesCommits()).isTrue();
	}

	@Test
	void flushesCommits_aGenericDatabase_flushesThem() {
		assertThat(config("{}").flushesCommits()).isTrue();
	}

	@Test
	void flushesCommits_setOff_doesNot() {
		assertThat(config("{\"technology\": \"postgresql\", \"commitFlush\": \"off\"}").flushesCommits()).isFalse();
	}

	@Test
	void unitsPerRead_aStrongReadOfANineKbItem_takesAUnitPerFourKb() {
		assertThat(config("{\"technology\": \"dynamodb\", \"itemSizeKb\": 9, \"consistentReads\": \"strong\"}")
			.unitsPerRead()).isEqualTo(3.0);
	}

	@Test
	void unitsPerRead_anEventualReadOfASmallItem_takesHalfAUnit() {
		assertThat(config("{\"technology\": \"dynamodb\", \"itemSizeKb\": 1}").unitsPerRead()).isEqualTo(0.5);
	}

	@Test
	void unitsPerWrite_anItemOfOneAndAHalfKb_takesTwoUnits() {
		assertThat(config("{\"technology\": \"dynamodb\", \"itemSizeKb\": 1.5}").unitsPerWrite()).isEqualTo(2.0);
	}

	@Test
	void readUnitsPerSecond_provisioned_isAsProvisioned() {
		assertThat(config(PROVISIONED).readUnitsPerSecond()).isEqualTo(500.0);
	}

	@Test
	void writeUnitsPerSecond_provisioned_isAsProvisioned() {
		assertThat(config(PROVISIONED).writeUnitsPerSecond()).isEqualTo(200.0);
	}

	@Test
	void readUnitsPerSecond_onDemand_isTheOnDemandUnits() {
		assertThat(config("{\"technology\": \"dynamodb\"}").readUnitsPerSecond())
			.isEqualTo(DatabaseConfig.ON_DEMAND_READ_UNITS);
	}

	@Test
	void writeUnitsPerSecond_onDemand_isTheOnDemandUnits() {
		assertThat(config("{\"technology\": \"dynamodb\"}").writeUnitsPerSecond())
			.isEqualTo(DatabaseConfig.ON_DEMAND_WRITE_UNITS);
	}

	private static DatabaseConfig config(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), DatabaseConfig.class);
	}

}
