package io.archsim.engine.sizing;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import io.archsim.model.component.ComponentSpec;

/**
 * The resource specs a component type has: the ones its config record has as settings.
 */
final class SpecKeys {

	private SpecKeys() {
	}

	/**
	 * The specs of a component type.
	 * @param spec the component type
	 * @return the specs its config has; empty for a part that is not sized
	 */
	static Set<Spec> of(ComponentSpec<?> spec) {
		Set<String> settings = Arrays.stream(spec.configType().getRecordComponents())
			.map(RecordComponent::getName)
			.collect(Collectors.toSet());
		Set<Spec> specs = EnumSet.noneOf(Spec.class);
		Arrays.stream(Spec.values()).filter((key) -> settings.contains(key.json())).forEach(specs::add);
		return specs;
	}

}
