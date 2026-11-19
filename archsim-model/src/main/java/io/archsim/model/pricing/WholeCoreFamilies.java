package io.archsim.model.pricing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

/**
 * The instance families whose vCPUs are whole cores, by provider. On most x86 instance
 * types a vCPU is one of the two hardware threads of a core; these are the families of
 * which the provider says that a vCPU is a core of its own. It is data, read once from
 * {@code io/archsim/model/pricing/whole-core-families.json}: each provider's families in
 * groups, each group with its reason and the page it rests on, or two pages where the
 * first says only which processor the families run on and the reason names the second. A
 * family is listed only with such pages, so one that is not listed may still be a
 * whole-core family.
 */
public final class WholeCoreFamilies {

	private static final String RESOURCE = "whole-core-families.json";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private WholeCoreFamilies() {
	}

	/**
	 * Whether a vCPU of an instance family is a whole core, as far as the list knows.
	 * @param provider the provider, such as {@code aws}
	 * @param family the family, such as {@code m7g}, as the provider's price snapshots
	 * name it
	 * @return whether the family is listed for the provider
	 */
	public static boolean has(String provider, String family) {
		return Listed.FAMILIES.getOrDefault(provider, Set.of()).contains(family);
	}

	/**
	 * Every listed family, in the groups that share a reason and a source.
	 * @return each provider's groups, in the file's order
	 */
	static Map<String, List<WholeCoreGroup>> byProvider() {
		return Listed.GROUPS;
	}

	/**
	 * The groups a file's JSON lists.
	 * @param file the file's JSON: an object of each provider's groups
	 * @return each provider's groups, in the file's order
	 * @throws IllegalStateException saying where the first thing wrong is, when a
	 * provider's groups are not in an array or a group is not a valid one
	 */
	static Map<String, List<WholeCoreGroup>> parse(JsonNode file) {
		Map<String, List<WholeCoreGroup>> byProvider = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> provider : file.properties()) {
			byProvider.put(provider.getKey(), readGroups(provider.getKey(), provider.getValue()));
		}
		return Collections.unmodifiableMap(byProvider);
	}

	private static List<WholeCoreGroup> readGroups(String provider, JsonNode listed) {
		if (!listed.isArray()) {
			throw new IllegalStateException(
					"%s is not valid (%s: a provider lists its groups in an array)".formatted(RESOURCE, provider));
		}
		List<WholeCoreGroup> groups = new ArrayList<>();
		for (int index = 0; index < listed.size(); index++) {
			groups.add(readGroup(listed.get(index), provider + "." + index));
		}
		return List.copyOf(groups);
	}

	/**
	 * One group of the file.
	 * @param json the group's JSON
	 * @param where its place in the file: its provider and its index among the provider's
	 * groups
	 * @return the group
	 */
	private static WholeCoreGroup readGroup(JsonNode json, String where) {
		try {
			return RecordCodec.read(json, WholeCoreGroup.class);
		}
		catch (BindingException ex) {
			BindingError first = ex.errors().getFirst();
			// an error at the group's own root has no field to name
			String field = first.location().isRoot() ? "" : "." + first.location();
			throw new IllegalStateException(
					"%s is not valid (%s%s: %s)".formatted(RESOURCE, where, field, first.message()), ex);
		}
	}

	private static Map<String, Set<String>> families(Map<String, List<WholeCoreGroup>> groups) {
		return groups.entrySet()
			.stream()
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
					(provider) -> provider.getValue()
						.stream()
						.flatMap((group) -> group.families().stream())
						.collect(Collectors.toUnmodifiableSet())));
	}

	/** The file's families, read the first time they are needed. */
	private static final class Listed {

		static final Map<String, List<WholeCoreGroup>> GROUPS = parse(JSON.readTree(ShippedFiles.read(RESOURCE)));

		static final Map<String, Set<String>> FAMILIES = families(GROUPS);

	}

}
