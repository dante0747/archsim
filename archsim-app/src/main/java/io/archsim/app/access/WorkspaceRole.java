package io.archsim.app.access;

import java.util.Arrays;
import java.util.Locale;

/**
 * A member's role in a workspace, each allowing what the one before it does and more.
 */
public enum WorkspaceRole {

	/** Reads the workspace's projects and runs. */
	VIEWER,

	/** Also changes projects and starts runs. */
	EDITOR,

	/** Also manages the workspace and its members. */
	OWNER;

	/**
	 * The role a membership row names.
	 * @param name {@code viewer}, {@code editor} or {@code owner}
	 * @return the role
	 * @throws IllegalArgumentException for any other name
	 */
	public static WorkspaceRole of(String name) {
		return Arrays.stream(values())
			.filter((role) -> role.json().equals(name))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("not a workspace role: " + name));
	}

	/**
	 * Whether this role allows what {@code minimum} does.
	 * @param minimum the role an action needs
	 * @return {@code true} when this role is at least that
	 */
	public boolean atLeast(WorkspaceRole minimum) {
		return compareTo(minimum) >= 0;
	}

	/**
	 * The role's name, as the database and the API write it.
	 * @return {@code viewer}, {@code editor} or {@code owner}
	 */
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

}
