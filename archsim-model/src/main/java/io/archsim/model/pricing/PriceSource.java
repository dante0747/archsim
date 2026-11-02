package io.archsim.model.pricing;

import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.ModelName;

/**
 * Where a snapshot's prices came from.
 *
 * @param name the source, such as {@code AWS Price List bulk API}
 * @param url where it is
 */
@ModelName("SourceModel")
public record PriceSource(@MaxLength(200) String name, @MaxLength(500) String url) {
}
