package io.archsim.engine.cost;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A third-party API's price per million calls, and where it came from (ADR-0081): the
 * price set on the part, else its technology preset's, else none, which leaves the API
 * out of the month and says so.
 */
class ApiPriceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The calls a month at 10 calls a second. */
	private static final double CALLS_A_MONTH = 10 * 3600 * 730.0;

	private final DesignAnalyzer analyzer = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void estimate_anApiWithAPriceSet_billsItsCallsAtThatPrice() {
		CostEstimate cost = estimate("\"technology\": \"email\", \"costPerMillionRequests\": 100");

		assertThat(api(cost).priceSource()).isEqualTo(PriceBasis.SET);
		assertThat(api(cost).monthly()).isCloseTo(CALLS_A_MONTH * 100 / 1e6, within(1e-6));
	}

	@Test
	void estimate_anApiAtAPriceOfZeroSet_billsNothingAtThePriceSet() {
		CostEstimate cost = estimate("\"technology\": \"email\", \"costPerMillionRequests\": 0");

		assertThat(api(cost).priceSource()).isEqualTo(PriceBasis.SET);
		assertThat(api(cost).monthly()).isZero();
		assertThat(cost.unpriced()).isEmpty();
	}

	/**
	 * The golden files' departures read an API line the Java prices at a preset's price,
	 * or not at all, as the frozen engine's 0 (ApiCostDepartures): this keeps a 0 set on
	 * the part from being lost on the way through a run unseen.
	 */
	@Test
	void run_anApiAtAPriceOfZeroSet_keepsItsPriceThroughTheRun() {
		JsonNode report = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped())
			.run(JSON.readTree(design("\"technology\": \"email\", \"costPerMillionRequests\": 0", "")
				.replace("\"rps\": 10}", "\"rps\": 10, \"durationS\": 3, \"warmupS\": 1}")), RunRequest.of(null));

		JsonNode line = report.at("/cost/byNode")
			.valueStream()
			.filter((cost) -> cost.path("id").stringValue("").equals("mail"))
			.findFirst()
			.orElseThrow();
		assertThat(List.of(line.path("priceSource").stringValue(""), line.path("unitPrice").doubleValue()))
			.containsExactly("set", 0.0);
		assertThat(report.at("/input/architecture/nodes/2/config/costPerMillionRequests"))
			.isEqualTo(JSON.readTree("0.0"));
	}

	@Test
	void estimate_anApiWithoutAPrice_billsItsTechnologyPresetsPrice() {
		CostEstimate cost = estimate("\"technology\": \"email\"");

		assertThat(api(cost).priceSource()).isEqualTo(PriceBasis.PRESET);
		assertThat(api(cost).unitPrice()).isEqualTo(1000.0);
		assertThat(api(cost).monthly()).isCloseTo(CALLS_A_MONTH * 1000 / 1e6, within(1e-6));
	}

	@Test
	void estimate_anApiWithoutAPrice_namesThePresetItsPriceIsFrom() {
		CostEstimate cost = estimate("\"technology\": \"email\"");

		assertThat(cost.notes()).contains("Mail is priced at the Email API preset's illustrative $1000.00 per million"
				+ " calls: set your provider's price on the part.");
	}

	@Test
	void estimate_anApiAtItsPresetsPrice_isPricedAtThePreset() {
		CostEstimate cost = estimate("\"technology\": \"email\", \"costPerMillionRequests\": 1000");

		assertThat(api(cost).priceSource()).isEqualTo(PriceBasis.PRESET);
		assertThat(cost.notes()).anyMatch((note) -> note.startsWith("Mail is priced at the Email API preset's"));
	}

	@Test
	void estimate_anApiAtItsPresetsPriceThatAPersonTyped_isPricedAsSet() {
		CostEstimate cost = estimate("\"technology\": \"email\", \"costPerMillionRequests\": 1000",
				"\"provenance\": {\"nodes/mail/costPerMillionRequests\": {\"kind\": \"assumed\"}},");

		assertThat(api(cost).priceSource()).isEqualTo(PriceBasis.SET);
	}

	@Test
	void estimate_onlyAnApiThatIsNotPriced_hasNoCost() {
		CostEstimate cost = this.analyzer.analyze(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "calls"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "mail", "type": "external_api", "label": "Mail"}],
				  "edges": [{"id": "clients-mail", "source": "clients", "target": "mail"}]},
				 "workload": {"rps": 10}}"""), null).cost();

		assertThat(cost.hasCost()).isFalse();
		assertThat(cost.notes()).anyMatch((note) -> note.startsWith("No component has a price yet"));
	}

	@Test
	void analyze_anApiWithoutAPrice_listsItsPriceAsAnIllustrativeDefault() {
		Input price = this.analyzer.analyze(JSON.readTree(design("\"technology\": \"generic\"", "")), null)
			.inputs()
			.stream()
			.filter((input) -> input.field().equals("costPerMillionRequests"))
			.findFirst()
			.orElseThrow();

		assertThat(
				List.of(price.value(), price.provenance().kind().json(), String.valueOf(price.provenance().source())))
			.containsExactly("None", "illustrative", "ArchSim default");
	}

	@Test
	void write_aCostThatLeavesAnApiOut_listsIt() {
		JsonNode written = RecordCodec.write(estimate("\"technology\": \"generic\""));

		assertThat(written.path("unpriced")).isEqualTo(JSON.readTree("[\"mail\"]"));
	}

	@Test
	void write_aCostThatPricesEveryPart_leavesTheListOut() {
		JsonNode written = RecordCodec.write(estimate("\"technology\": \"email\""));

		assertThat(written.has("unpriced")).isFalse();
	}

	@Test
	void estimate_anApiWithoutAPriceOrAPresetPrice_isNotPriced() {
		CostEstimate cost = estimate("\"technology\": \"payments\"");

		assertThat(api(cost).unitPrice()).isNull();
		assertThat(api(cost).priceSource()).isNull();
		assertThat(api(cost).monthly()).isZero();
	}

	@Test
	void estimate_anApiThatIsNotPriced_isListedAsLeftOutOfTheMonth() {
		CostEstimate cost = estimate("\"technology\": \"generic\"");

		assertThat(cost.unpriced()).containsExactly("mail");
		assertThat(cost.notes()).contains("Not priced: Mail (no price per million calls set, and no preset price for"
				+ " its technology): the month leaves it out.");
	}

	@Test
	void estimate_everyPartPriced_leavesNothingOut() {
		CostEstimate cost = estimate("\"technology\": \"email\"");

		assertThat(cost.unpriced()).isEmpty();
		assertThat(cost.notes()).noneMatch((note) -> note.startsWith("Not priced"));
	}

	private CostEstimate estimate(String config) {
		return estimate(config, "");
	}

	private CostEstimate estimate(String config, String provenance) {
		return this.analyzer.analyze(JSON.readTree(design(config, provenance)), null).cost();
	}

	private static String design(String config, String provenance) {
		return """
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "calls"}, %s
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"replicas": 1, "costPerHour": 0.5}},
				    {"id": "mail", "type": "external_api", "label": "Mail", "config": {%s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				            {"id": "api-mail", "source": "api", "target": "mail"}]},
				 "workload": {"rps": 10}}""".formatted(provenance, config);
	}

	private static CostLine api(CostEstimate cost) {
		return cost.byNode().stream().filter((line) -> line.id().equals("mail")).findFirst().orElseThrow();
	}

}
