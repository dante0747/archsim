package io.archsim.app.meta;

import org.springframework.stereotype.Service;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.model.document.ArchitectureDocument;

/**
 * Whether this server can serve, and what it runs.
 */
@Service
class MetaService {

	private static final String DATABASE = "postgresql";

	private final DatabaseProbe database;

	private final ArchsimProperties properties;

	MetaService(DatabaseProbe database, ArchsimProperties properties) {
		this.database = database;
		this.properties = properties;
	}

	/**
	 * Whether the API reaches its database.
	 * @return the health
	 */
	Health health() {
		return this.database.answers() ? Health.OK : Health.DEGRADED;
	}

	/**
	 * The versions and limits this server runs with.
	 * @return the metadata
	 */
	Meta meta() {
		Meta.Limits limits = new Meta.Limits(this.properties.maxRequests(), this.properties.maxWallSeconds(),
				this.properties.concurrentRuns());
		return new Meta(EngineVersions.ENGINE, ArchitectureDocument.SCHEMA_VERSION, EngineVersions.REPORT, DATABASE,
				limits);
	}

}
