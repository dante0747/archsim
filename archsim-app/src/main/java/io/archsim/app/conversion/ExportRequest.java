package io.archsim.app.conversion;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.export.ExportFormat;
import io.archsim.model.codec.IgnoreUnknownKeys;

/**
 * What {@code POST /export} takes.
 *
 * @param document the design
 * @param format what to write it as
 */
@IgnoreUnknownKeys
record ExportRequest(ObjectNode document, ExportFormat format) {

}
