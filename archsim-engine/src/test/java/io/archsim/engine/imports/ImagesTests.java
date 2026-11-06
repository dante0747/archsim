package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;

import io.archsim.engine.imports.ImageKind.Use;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a container image is, by its full repository name.
 */
class ImagesTests {

	@Test
	void repository_registryHostTagAndDigest_areLeftOut() {
		assertThat(Images.repository("registry.local:5000/library/Postgres:16@sha256:ab"))
			.isEqualTo("library/postgres");
	}

	@Test
	void classify_distributionOfATechnology_isItsInfrastructure() {
		assertThat(Images.classify("bitnami/postgresql:16"))
			.extracting(ImageKind::use, ImageKind::type, ImageKind::technology)
			.containsExactly(Use.INFRASTRUCTURE, "database", "postgresql");
	}

	@Test
	void classify_exporterNamedAfterATechnology_isATool() {
		assertThat(Images.classify("prom/mysqld-exporter:v0.15.1").use()).isEqualTo(Use.TOOL);
	}

	@Test
	void classify_repositoryNamedLikeAnotherTechnologysPath_isAnApplication() {
		assertThat(Images.classify("ghcr.io/acme/server:1.0").use()).isEqualTo(Use.APPLICATION);
	}

}
