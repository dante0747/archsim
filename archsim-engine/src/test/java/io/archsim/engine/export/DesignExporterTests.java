package io.archsim.engine.export;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A design exported as a run would simulate it: loaded, auto-sized, then written.
 */
class DesignExporterTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode DANGLING_LINK = JSON.readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Shop"},
			 "architecture": {
			   "nodes": [
			     {"id": "clients", "type": "traffic_source", "config": {}},
			     {"id": "api", "type": "service", "config": {"serviceTimeMs": {"kind": "constant", "value": 10}}}],
			   "edges": [
			     {"id": "clients-api", "source": "clients", "target": "api"},
			     {"id": "api-gone", "source": "api", "target": "gone"}]},
			 "workload": {"rps": 100, "readRatio": 0.8, "durationS": 60}}""");

	private final DesignExporter exporter = new DesignExporter(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void export_linkToAPartTheDesignDoesNotHave_writesTheDesignWithoutIt() {
		String diagram = this.exporter.export(DANGLING_LINK, ExportFormat.MERMAID);

		assertThat(diagram).endsWith("  clients --> api\n");
	}

	@Test
	void export_documentItCannotRead_throwsItsIssues() {
		JsonNode notADesign = JSON.readTree("{\"kind\": \"nope\"}");

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> this.exporter.export(notADesign, ExportFormat.COMPOSE))
			.satisfies((ex) -> assertThat(ex.issues()).extracting(Issue::code).containsExactly("document.version"));
	}

}
