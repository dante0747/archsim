package io.archsim.app.projects;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Access;
import io.archsim.app.examples.ExampleService;
import io.archsim.app.store.Times;

/**
 * The {@code seed} role's work: one project per example, in the default workspace, its
 * design read and written back as the API saves one, unless a project of the example's
 * name exists in any workspace. The schema is the API's, so the role never migrates.
 */
@Component
@Profile("seed")
class ExampleSeeding implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(ExampleSeeding.class);

	private final ExampleService examples;

	private final Designs designs;

	private final ProjectRepository projects;

	private final Clock clock;

	ExampleSeeding(ExampleService examples, Designs designs, ProjectRepository projects, Clock clock) {
		this.examples = examples;
		this.designs = designs;
		this.projects = projects;
		this.clock = clock;
	}

	/**
	 * Makes the projects, then says how many.
	 * @param args the role's arguments, which it does not read
	 */
	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		Set<String> taken = this.projects.names();
		Instant now = Times.now(this.clock);
		int made = 0;
		for (String id : this.examples.ids()) {
			ObjectNode design = this.designs.normalize(this.examples.design(id), null);
			if (taken.contains(design.path("metadata").path("name").asString())) {
				continue;
			}
			// past ProjectChanges: the seed has no principal and writes no history entry,
			// as archsim-api seed did
			this.projects
				.insert(new NewProject(Access.DEFAULT_WORKSPACE_ID, design, null, null).firstRevision(null, now));
			made++;
		}
		LOGGER.info("seeded {} example project(s)", made);
	}

}
