package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The figures a recorded case lists as the Java's own read as the frozen engine's only
 * where the Java's figure is the one listed; an item of a list it lists is one the Java
 * adds, left out only where the Java's item is the one listed.
 */
class OwnFiguresTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode PYTHON = JSON.readTree("""
			{"busy": 0.5, "review": [{"id": "search"}, {"id": "spof"}]}""");

	@Test
	void asRecorded_aListedFigureTheJavaWrites_readsAsTheFrozenEngines() {
		JsonNode java = JSON.readTree("""
				{"busy": 0.8, "review": [{"id": "search"}, {"id": "spof"}]}""");

		JsonNode recorded = OwnFigures.asRecorded(java, PYTHON, JSON.readTree("{\"/busy\": 0.8}"));

		assertThat(recorded).isEqualTo(PYTHON);
	}

	@Test
	void asRecorded_aListedFigureThatMovedAgain_staysTheJavas() {
		JsonNode java = JSON.readTree("""
				{"busy": 0.9, "review": [{"id": "search"}, {"id": "spof"}]}""");

		JsonNode recorded = OwnFigures.asRecorded(java, PYTHON, JSON.readTree("{\"/busy\": 0.8}"));

		assertThat(recorded.get("busy").doubleValue()).isEqualTo(0.9);
	}

	@Test
	void asRecorded_aListedItemTheJavaAdds_isLeftOut() {
		JsonNode java = JSON.readTree("""
				{"busy": 0.5, "review": [{"id": "search"}, {"id": "gateway"}, {"id": "spof"}]}""");

		JsonNode recorded = OwnFigures.asRecorded(java, PYTHON, JSON.readTree("""
				{"/review/1": {"id": "gateway"}}"""));

		assertThat(recorded).isEqualTo(PYTHON);
	}

	@Test
	void asRecorded_twoListedItemsTheJavaAddsToOneList_areBothLeftOut() {
		JsonNode java = JSON.readTree("""
				{"busy": 0.5, "review": [{"id": "search"}, {"id": "gateway"}, {"id": "cache"}, {"id": "spof"}]}""");

		JsonNode recorded = OwnFigures.asRecorded(java, PYTHON, JSON.readTree("""
				{"/review/1": {"id": "gateway"}, "/review/2": {"id": "cache"}}"""));

		assertThat(recorded).isEqualTo(PYTHON);
	}

	@Test
	void asRecorded_aListedItemTheJavaDoesNotAddThere_staysToShow() {
		JsonNode java = JSON.readTree("""
				{"busy": 0.5, "review": [{"id": "search"}, {"id": "spof"}, {"id": "gateway"}]}""");

		JsonNode recorded = OwnFigures.asRecorded(java, PYTHON, JSON.readTree("""
				{"/review/1": {"id": "gateway"}}"""));

		assertThat(recorded).isEqualTo(java);
	}

}
