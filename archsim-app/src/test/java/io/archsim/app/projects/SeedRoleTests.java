package io.archsim.app.projects;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.apitests.Examples;
import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * The application started as {@code archsim-app seed}: one project per example, in the
 * default workspace, unless a project of the example's name exists anywhere; it never
 * migrates. Each test starts the role on a database of its own, with two examples.
 */
@ExtendWith(OutputCaptureExtension.class)
class SeedRoleTests {

	@TempDir
	private Path examples;

	@BeforeEach
	void twoExamples() throws IOException {
		Files.writeString(this.examples.resolve("a-first.json"), design("First example", "the first"));
		Files.writeString(this.examples.resolve("b-second.json"), design("Second example", null));
	}

	@Test
	void seed_migratedDatabase_makesAProjectPerExampleInTheDefaultWorkspace(CapturedOutput output) {
		String database = TestDatabases.migratedJdbcUrl();

		seed(database);

		assertThat(projects(database)).containsExactly("default First example the first 1 null null",
				"default Second example null 1 null null");
		assertThat(output).contains("seeded 2 example project(s)");
	}

	@Test
	void seed_againOnTheSameDatabase_makesNoMoreProjects(CapturedOutput output) {
		String database = TestDatabases.migratedJdbcUrl();
		seed(database);

		seed(database);

		assertThat(projects(database)).hasSize(2);
		assertThat(output).contains("seeded 0 example project(s)");
	}

	@Test
	void seed_anExampleWhoseNameATeamUses_leavesThatExampleOut() {
		String database = TestDatabases.migratedJdbcUrl();
		JdbcClient jdbc = jdbc(database);
		jdbc.sql("INSERT INTO workspaces (id, name, kind, created_by, created_at) "
				+ "VALUES ('team1', 'Team', 'team', 'local', now())")
			.update();
		jdbc.sql("INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at, "
				+ "updated_at) VALUES ('p1', 'team1', 'First example', '{}', '1.7', 1, now(), now())")
			.update();

		seed(database);

		assertThat(projects(database)).containsExactly("default Second example null 1 null null",
				"team1 First example null 1 null null");
	}

	@Test
	void seed_emptyDatabase_isRefusedUntilTheApiMigratesIt() {
		String database = TestDatabases.freshJdbcUrl();

		assertThatException().isThrownBy(() -> seed(database))
			.havingRootCause()
			.withMessage("the database has no schema history yet, this seed needs V2; start the API first");
	}

	@Test
	void start_withoutTheSeedRole_makesNoProjects() {
		String database = TestDatabases.migratedJdbcUrl();

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ArchsimApplication.class)
			.web(WebApplicationType.NONE)
			.run(settings(database))) {
			assertThat(context.getBeansOfType(ExampleSeeding.class)).isEmpty();
			assertThat(projects(database)).isEmpty();
		}
	}

	@Test
	void seed_anExample_isStoredWithItsDefaultsFilledIn() {
		String database = TestDatabases.migratedJdbcUrl();

		seed(database);

		// read and written back as a design, as the API saves one: a default the file
		// leaves out is there
		assertThat(jdbc(database)
			.sql("SELECT document->'workload'->>'keyCount' FROM projects WHERE name = 'First example'")
			.query(String.class)
			.single()).isNotNull();
	}

	private void seed(String database) {
		new SpringApplicationBuilder(ArchsimApplication.class).web(Role.SEED.webApplicationType())
			.profiles(Role.SEED.profile())
			.run(settings(database))
			.close();
	}

	// the database, the two examples, and the seed's own log at INFO, as in production
	private String[] settings(String database) {
		return new String[] { "--archsim.database-url=" + database, "--archsim.examples-dir=" + this.examples,
				"--logging.level.io.archsim.app.projects=info" };
	}

	// each project: its workspace, name, description, revision, creator and example
	private static List<String> projects(String database) {
		return jdbc(database)
			.sql("SELECT workspace_id, name, description, revision, created_by, source_example FROM projects "
					+ "ORDER BY workspace_id, name")
			.query((row, number) -> String.join(" ", row.getString(1), row.getString(2), row.getString(3),
					String.valueOf(row.getInt(4)), row.getString(5), row.getString(6)))
			.list();
	}

	private static JdbcClient jdbc(String database) {
		return JdbcClient.create(new DriverManagerDataSource(database));
	}

	// a template's design renamed, with the description given or none
	private static String design(String name, @Nullable String description) {
		ObjectNode design = Examples.load("queueing-lab");
		ObjectNode metadata = (ObjectNode) design.path("metadata");
		metadata.put("name", name);
		if (description != null) {
			metadata.put("description", description);
		}
		else {
			metadata.remove("description");
		}
		return design.toString();
	}

}
