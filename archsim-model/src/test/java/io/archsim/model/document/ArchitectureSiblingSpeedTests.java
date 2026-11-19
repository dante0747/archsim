package io.archsim.model.document;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The sibling speed a design sets for every part that sets none (schema 1.11, ADR-0091).
 */
class ArchitectureSiblingSpeedTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void read_withoutASiblingSpeed_setsNone() {
		assertThat(architecture("{}").siblingSpeed()).isNull();
	}

	@Test
	void read_aSiblingSpeed_isTaken() {
		assertThat(architecture("{\"siblingSpeed\": 0.5}").siblingSpeed()).isEqualTo(0.5);
	}

	@Test
	void read_wholeCores_areTaken() {
		assertThat(architecture("{\"siblingSpeed\": 1}").siblingSpeed()).isEqualTo(1.0);
	}

	@Test
	void read_aSiblingSpeedOfZero_isRefused() {
		assertThatExceptionOfType(BindingException.class).isThrownBy(() -> architecture("{\"siblingSpeed\": 0}"));
	}

	@Test
	void read_aSiblingSpeedAboveOne_isRefused() {
		assertThatExceptionOfType(BindingException.class).isThrownBy(() -> architecture("{\"siblingSpeed\": 1.5}"));
	}

	@Test
	void write_withoutASiblingSpeed_leavesItOut() {
		assertThat(RecordCodec.write(architecture("{}")).has("siblingSpeed")).isFalse();
	}

	@Test
	void write_aSiblingSpeed_keepsIt() {
		assertThat(RecordCodec.write(architecture("{\"siblingSpeed\": 1}")).path("siblingSpeed").doubleValue())
			.isEqualTo(1.0);
	}

	private static Architecture architecture(String json) {
		return RecordCodec.read((ObjectNode) JSON.readTree(json), Architecture.class);
	}

}
