package io.archsim.model.catalog;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.codec.Choice;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Lt;
import io.archsim.model.codec.Ui;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/** How a config record's settings are described to the editor's forms. */
class FieldDescriptorsTests {

	@Test
	void humanize_camelCaseName_readsAsWords() {
		assertThat(FieldDescriptors.humanize("maxAttempts")).isEqualTo("Max attempts");
		assertThat(FieldDescriptors.humanize("cpuCores")).isEqualTo("Cpu cores");
	}

	@Test
	void of_annotatedField_describesItsLabelKindBoundsAndHints() {
		JsonNode replicas = fieldOf("replicas");

		assertThat(replicas.path("label").asString()).isEqualTo("Replicas");
		assertThat(replicas.path("kind").asString()).isEqualTo("integer");
		assertThat(replicas.path("description").asString()).isEqualTo("Copies of the service.");
		assertThat(replicas.path("default").asInt()).isEqualTo(2);
		assertThat(replicas.path("min").asInt()).isEqualTo(1);
		assertThat(replicas.path("max").asInt()).isEqualTo(64);
		assertThat(replicas.path("group").asString()).isEqualTo("Capacity");
		assertThat(replicas.path("headline").asBoolean()).isTrue();
		assertThat(replicas.has("exclusiveMin")).isFalse();
	}

	@Test
	void of_nullableFieldWithExclusiveBound_isNullableWithAnExclusiveMinimum() {
		JsonNode timeout = fieldOf("timeoutMs");

		assertThat(timeout.path("nullable").asBoolean()).isTrue();
		assertThat(timeout.path("default").isNull()).isTrue();
		assertThat(timeout.path("exclusiveMin").asBoolean()).isTrue();
		assertThat(timeout.path("label").asString()).isEqualTo("Timeout");
	}

	@Test
	void of_listWithKindHint_takesTheHintWithoutOptions() {
		JsonNode types = fieldOf("types");

		assertThat(types.path("kind").asString()).isEqualTo("routing");
		assertThat(types.has("options")).isFalse();
	}

	@Test
	void of_listWithoutHint_offersTheRequestTypes() {
		JsonNode carried = fieldOf("carried");

		assertThat(carried.path("kind").asString()).isEqualTo("requestTypes");
		assertThat(carried.path("options")).containsExactly(stringNode("read"), stringNode("write"));
		assertThat(carried.path("default").isArray()).isTrue();
	}

	@Test
	void of_exclusiveUpperBound_isTheMaximum() {
		JsonNode share = fieldOf("share");

		assertThat(share.path("max").asInt()).isEqualTo(1);
	}

	@Test
	void of_integerChoices_areEnumOptionsInWrittenOrder() {
		JsonNode threads = fieldOf("threads");

		assertThat(threads.path("options").valueStream().map(JsonNode::asInt)).containsExactly(2, 1);
	}

	private static JsonNode fieldOf(String key) {
		ArrayNode fields = FieldDescriptors.of(Sample.class);
		return fields.valueStream()
			.filter((field) -> field.path("key").asString().equals(key))
			.findFirst()
			.orElseThrow();
	}

	private static JsonNode stringNode(String text) {
		return JsonNodeFactory.instance.stringNode(text);
	}

	/**
	 * A config with one field of each case the descriptors handle.
	 *
	 * @param replicas a bounded integer with hints
	 * @param timeoutMs an optional number with an exclusive bound
	 * @param types a list the hints show as another kind
	 * @param carried a list of request types
	 * @param share a number below an exclusive upper bound
	 * @param threads an integer that is one of a few
	 */
	record Sample(
			@Default("2") @Description("Copies of the service.") @Ui(group = "Capacity",
					headline = true) @Ge(1) @Le(64) int replicas,
			@Description("Time to wait.") @Ui(label = "Timeout", unit = "ms") @Gt(0) @Nullable Double timeoutMs,
			@Default("[]") @Description("Routes.") @Ui(kind = "routing") List<RequestType> types,
			@Default("[]") @Description("Request types.") List<RequestType> carried,
			@Default("0.5") @Description("A share.") @Ge(0) @Lt(1) double share,
			@Default("2") @Description("Threads per core.") @Choice(2) @Choice(1) int threads) {
	}

}
