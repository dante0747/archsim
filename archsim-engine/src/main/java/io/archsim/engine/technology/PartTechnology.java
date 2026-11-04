package io.archsim.engine.technology;

import io.archsim.model.component.Tier;

/**
 * A part's technology and its tier, written by {@code RecordCodec}.
 *
 * @param id the part's id
 * @param label the part's label, or its id when it has none
 * @param type the part's type
 * @param technology its technology, such as {@code postgresql}
 * @param tier how well ArchSim knows the technology
 */
public record PartTechnology(String id, String label, String type, String technology, Tier tier) {
}
