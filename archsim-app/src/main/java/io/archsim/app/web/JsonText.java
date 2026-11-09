package io.archsim.app.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * JSON written elsewhere, by the engine or into the database, sent to the client as it
 * is: reading and writing it again would respell numbers such as {@code 1e-05}.
 *
 * @param json the JSON text
 */
public record JsonText(String json) {

	/**
	 * The text as a {@code 200} answer.
	 * @return the answer, typed as JSON
	 */
	public ResponseEntity<String> answer() {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(this.json);
	}

}
