package io.archsim.app;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.boot.WebApplicationType;

/**
 * What a started {@code archsim-app} does, named by its first argument: one image runs as
 * any of them, and each compose service names its own (ADR-0014).
 */
public enum Role {

	/** Serves the HTTP API, and migrates the database first unless told not to. */
	API(WebApplicationType.SERVLET, Lifetime.UNTIL_STOPPED),

	/** Brings the database to the latest schema, then exits. */
	MIGRATE(WebApplicationType.NONE, Lifetime.ONE_SHOT),

	/**
	 * Simulates the runs the API queues until it is stopped, on a database the API has
	 * migrated (ADR-0053).
	 */
	WORKER(WebApplicationType.NONE, Lifetime.UNTIL_STOPPED);

	private final WebApplicationType webApplicationType;

	private final Lifetime lifetime;

	Role(WebApplicationType webApplicationType, Lifetime lifetime) {
		this.webApplicationType = webApplicationType;
		this.lifetime = lifetime;
	}

	/**
	 * The role a command line names in its first argument.
	 * @param args the command line
	 * @return the role
	 * @throws IllegalArgumentException when the first argument names no role
	 */
	public static Role of(String[] args) {
		String usage = "start archsim-app as one of " + Arrays.toString(names()) + ", e.g. `archsim-app api`";
		if (args.length == 0) {
			throw new IllegalArgumentException(usage);
		}
		return Arrays.stream(values())
			.filter((role) -> role.profile().equals(args[0]))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unknown role '" + args[0] + "': " + usage));
	}

	/**
	 * The Spring profile of the role, also its name on the command line.
	 * @return the profile, such as {@code api}
	 */
	public String profile() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The kind of application Spring Boot starts for the role: a servlet one when it
	 * serves HTTP, none otherwise.
	 * @return the web application type
	 */
	public WebApplicationType webApplicationType() {
		return this.webApplicationType;
	}

	/**
	 * Whether the role exits once it has started, instead of running until it is stopped.
	 * @return {@code true} for a one-shot role
	 */
	public boolean exitsWhenStarted() {
		return this.lifetime == Lifetime.ONE_SHOT;
	}

	private static String[] names() {
		return Arrays.stream(values()).map(Role::profile).toArray(String[]::new);
	}

	/**
	 * How long a started role runs.
	 */
	private enum Lifetime {

		/** It does its work, then exits. */
		ONE_SHOT,

		/** It runs until it is stopped. */
		UNTIL_STOPPED

	}

}
