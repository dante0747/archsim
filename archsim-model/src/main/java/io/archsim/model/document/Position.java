package io.archsim.model.document;

import io.archsim.model.codec.Default;

/**
 * Where a node sits on the canvas. Rendering only: it never changes a result.
 *
 * @param x the horizontal position
 * @param y the vertical position
 */
public record Position(@Default("0.0") double x, @Default("0.0") double y) {
}
