package io.archsim.app.conversion;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.imports.ImportFormat;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /import} takes.
 *
 * @param format what the text is
 * @param text the file's content (Terraform: the JSON of {@code terraform show -json})
 * @param name the design's name, if not the one the text gives
 */
@IgnoreUnknownKeys
record ImportRequest(ImportFormat format, String text, @MinLength(1) @MaxLength(120) @Nullable String name) {

}
