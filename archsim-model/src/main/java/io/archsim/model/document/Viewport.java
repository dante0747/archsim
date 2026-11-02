package io.archsim.model.document;

import io.archsim.model.codec.Default;

/**
 * The part of the canvas the editor shows. Rendering only.
 *
 * @param x the horizontal offset
 * @param y the vertical offset
 * @param zoom the zoom factor
 */
public record Viewport(
        @Default("0.0") double x,
        @Default("0.0") double y,
        @Default("1.0") double zoom) {}
