package io.archsim.app.meta;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the API is healthy, and what it runs.
 */
@RestController
class MetaController {

	private final MetaService meta;

	MetaController(MetaService meta) {
		this.meta = meta;
	}

	/**
	 * {@code 200} when the API reaches its database, {@code 503} otherwise.
	 * @return the health
	 */
	@GetMapping("/health")
	ResponseEntity<Health> health() {
		Health health = this.meta.health();
		HttpStatus status = Health.OK.equals(health) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
		return ResponseEntity.status(status).body(health);
	}

	/**
	 * ArchSim's version, the engine, schema and report versions, the database and the run
	 * limits.
	 * @return the metadata
	 */
	@GetMapping("/meta")
	Meta meta() {
		return this.meta.meta();
	}

}
