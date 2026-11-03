package io.archsim.app.limits;

import java.util.OptionalInt;

import org.springframework.stereotype.Component;

/**
 * What a workspace's kind allows, as the Python API's limit profiles say. A sandbox holds
 * at most five projects whether or not the hosted limits are on; personal and team
 * workspaces hold any number. The run limits come with the runs (slice 4 of the port).
 */
@Component
public class WorkspaceLimits {

	/** The kind of workspace a visitor tries ArchSim in. */
	private static final String SANDBOX = "sandbox";

	private static final int SANDBOX_PROJECTS = 5;

	/**
	 * The most projects a workspace of a kind holds.
	 * @param kind {@code personal}, {@code team} or {@code sandbox}
	 * @return the cap, or empty when there is none
	 */
	public OptionalInt maxProjects(String kind) {
		return SANDBOX.equals(kind) ? OptionalInt.of(SANDBOX_PROJECTS) : OptionalInt.empty();
	}

}
