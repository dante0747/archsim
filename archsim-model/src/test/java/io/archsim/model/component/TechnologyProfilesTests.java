package io.archsim.model.component;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.model.codec.JsonEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** What each of a component type's technologies does by default. */
class TechnologyProfilesTests {

	@Test
	void build_technologyWithoutProfile_isRefused() {
		TechnologyProfiles.Builder<Two> onlyOne = TechnologyProfiles.builder(Two.class)
			.with(Two.ONE, TechnologyProfile.core());

		assertThatIllegalArgumentException().isThrownBy(onlyOne::build)
			.withMessage("technologies without a profile: [TWO]");
	}

	@Test
	void with_technologyGivenTwice_isRefused() {
		TechnologyProfiles.Builder<Two> builder = TechnologyProfiles.builder(Two.class)
			.with(Two.ONE, TechnologyProfile.core());

		assertThatIllegalArgumentException().isThrownBy(() -> builder.with(Two.ONE, TechnologyProfile.experimental()))
			.withMessage("technology given twice: ONE");
	}

	@Test
	void byName_everyTechnology_isKeyedByItsJsonNameInDeclarationOrder() {
		TechnologyProfiles<Two> profiles = TechnologyProfiles.builder(Two.class)
			.with(Two.TWO, TechnologyProfile.experimental())
			.with(Two.ONE, TechnologyProfile.core())
			.build();

		Map<String, TechnologyProfile> byName = profiles.byName();

		assertThat(byName).containsExactly(Map.entry("one", TechnologyProfile.core()),
				Map.entry("two", TechnologyProfile.experimental()));
	}

	@Test
	void number_fieldWithoutDefault_isEmpty() {
		TechnologyProfile profile = TechnologyProfile.core().withoutDefault("upstreamTimeoutMs");

		assertThat(profile.number("upstreamTimeoutMs", null)).isEmpty();
	}

	@Test
	void values_fieldWithoutDefault_listsItAsNull() {
		TechnologyProfile profile = TechnologyProfile.core().withoutDefault("upstreamTimeoutMs");

		assertThat(profile.values()).containsEntry("upstreamTimeoutMs", null);
	}

	/** Two technologies, enough to tell a missing or doubled profile apart. */
	private enum Two implements JsonEnum {

		/** The first technology. */
		ONE,
		/** The second technology. */
		TWO;

		@Override
		public String json() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
