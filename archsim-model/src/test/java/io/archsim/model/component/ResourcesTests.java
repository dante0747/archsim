package io.archsim.model.component;

import org.junit.jupiter.api.Test;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.HostSpec;

import static org.assertj.core.api.Assertions.assertThat;

/** What the resource settings of parts share. */
class ResourcesTests {

	@Test
	void defaultSiblingSpeed_always_isTheHostMachinesDefault() {
		double host = RecordCodec.defaults(HostSpec.class).path("siblingSpeed").doubleValue();

		assertThat(Resources.DEFAULT_SIBLING_SPEED).isEqualTo(host);
	}

}
