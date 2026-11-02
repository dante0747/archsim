package io.archsim.model.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.archsim.model.document.ArchitectureDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Documents of older schema versions are upgraded step by step; anything else is refused with a reason. */
class UpgradesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode document(String version) {
        return (ObjectNode) JSON.readTree("{\"schemaVersion\": \"%s\", \"workload\": {\"rps\": 5}}".formatted(version));
    }

    @Test
    void the_chain_runs_from_1_0_to_the_current_version_without_gaps() {
        List<Upgrade> chain = Upgrades.CHAIN;
        assertThat(chain.getFirst().from()).isEqualTo("1.0");
        assertThat(chain.getLast().to()).isEqualTo(ArchitectureDocument.SCHEMA_VERSION);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).from()).isEqualTo(chain.get(i - 1).to());
        }
    }

    @Test
    void an_older_document_is_upgraded_one_step_at_a_time_with_a_note_for_each() {
        Upgraded upgraded = Upgrades.toCurrent(document("1.5"));
        assertThat(upgraded.notes()).containsExactly("migrated schema 1.5 -> 1.6", "migrated schema 1.6 -> 1.7");
        assertThat(upgraded.document().get("schemaVersion").stringValue()).isEqualTo("1.7");
        assertThat(upgraded.document().get("workload")).isEqualTo(JSON.readTree("{\"rps\": 5}"));
    }

    @Test
    void a_current_document_is_left_as_it_is() {
        Upgraded upgraded = Upgrades.toCurrent(document("1.7"));
        assertThat(upgraded.notes()).isEmpty();
        assertThat(upgraded.document()).isEqualTo(document("1.7"));
    }

    @Test
    void the_input_is_never_changed() {
        ObjectNode input = document("1.0");
        Upgrades.toCurrent(input);
        assertThat(input).isEqualTo(document("1.0"));
    }

    @Test
    void a_version_written_as_a_number_is_read_as_python_writes_it() {
        JsonNode input = JSON.readTree("{\"schemaVersion\": 1.6}");
        assertThat(Upgrades.toCurrent(input).notes()).containsExactly("migrated schema 1.6 -> 1.7");
    }

    @Test
    void a_step_that_changes_the_document_is_applied_in_turn() {
        List<Upgrade> chain = List.of(new Upgrade("2.0", "2.1", "renames rps", doc -> {
            doc.set("load", doc.remove("rps"));
            return doc;
        }));
        Upgraded upgraded = Upgrades.upgrade(JSON.readTree("{\"schemaVersion\": \"2.0\", \"rps\": 5}"), "2.1", chain);
        assertThat(upgraded.document()).isEqualTo(JSON.readTree("{\"schemaVersion\": \"2.1\", \"load\": 5}"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            []                         | an architecture document must be a JSON object
            {}                         | missing 'schemaVersion'; is this an ArchSim architecture document?
            {"schemaVersion": null}    | missing 'schemaVersion'; is this an ArchSim architecture document?
            {"schemaVersion": "one"}   | 'one' is not a valid schemaVersion
            {"schemaVersion": ""}      | '' is not a valid schemaVersion
            {"schemaVersion": true}    | 'True' is not a valid schemaVersion
            {"schemaVersion": "1.10"}  | this document uses schema 1.10, newer than the supported 1.7. Upgrade ArchSim to open it.
            {"schemaVersion": "1.7.0"} | this document uses schema 1.7.0, newer than the supported 1.7. Upgrade ArchSim to open it.
            {"schemaVersion": "0.9"}   | no migration path from schema 0.9 to 1.7
            {"schemaVersion": "1.3.0"} | no migration path from schema 1.3.0 to 1.7
            """)
    void a_document_that_cannot_be_upgraded_is_refused_saying_why(String input, String message) {
        assertThatThrownBy(() -> Upgrades.toCurrent(JSON.readTree(input)))
                .isInstanceOf(UpgradeException.class)
                .hasMessage(message);
    }

    @Test
    void versions_are_ordered_as_tuples_of_integers() {
        assertThat(SchemaVersion.parse("1.10")).isGreaterThan(SchemaVersion.parse("1.7"));
        assertThat(SchemaVersion.parse("1.7.0")).isGreaterThan(SchemaVersion.parse("1.7"));
        assertThat(SchemaVersion.parse("0.9")).isLessThan(SchemaVersion.parse("1.0"));
        assertThat(SchemaVersion.parse(" 1 . 07")).isEqualByComparingTo(SchemaVersion.parse("1.7"));
    }
}
