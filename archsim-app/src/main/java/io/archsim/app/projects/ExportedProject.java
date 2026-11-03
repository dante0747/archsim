package io.archsim.app.projects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

/**
 * A project as a file to download.
 *
 * @param fileName the file's name, such as {@code checkout.archsim.json}
 * @param document the design
 */
record ExportedProject(String fileName, ObjectNode document) {

	/**
	 * The file as a download.
	 * @return the design as JSON, as an attachment with the file's name
	 */
	ResponseEntity<ObjectNode> answer() {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + this.fileName + "\"")
			.body(this.document);
	}

}
