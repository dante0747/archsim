package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the design is called and is about.
 *
 * @param name the design's name
 * @param description a longer description
 * @param tags labels to find it by
 */
public record Metadata(
        @Default("Untitled architecture") @MinLength(1) @MaxLength(120)
        String name,

        @MaxLength(4000) @Nullable String description,
        @Default("[]") @MaxItems(20) List<String> tags) {

    public Metadata {
        tags = List.copyOf(tags);
    }
}
