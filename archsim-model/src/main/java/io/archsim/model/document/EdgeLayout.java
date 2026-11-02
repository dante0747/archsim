package io.archsim.model.document;

import io.archsim.model.codec.Description;
import org.jspecify.annotations.Nullable;

/**
 * Which side of each component a link attaches to on the canvas. Rendering only.
 *
 * @param sourceSide the side of the calling component; {@code null}: the editor chooses
 * @param targetSide the side of the called component; {@code null}: the editor chooses
 */
@Description("Which side of each component a link attaches to on the canvas (rendering only).")
public record EdgeLayout(
        @Nullable EdgeSide sourceSide, @Nullable EdgeSide targetSide) {}
