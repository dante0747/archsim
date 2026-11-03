package io.archsim.app.conversion;

import tools.jackson.databind.node.ObjectNode;

/**
 * What {@code POST /export} takes.
 *
 * @param document the design
 * @param format what to write it as
 */
record ExportRequest(ObjectNode document, ExportFormat format) {

}
