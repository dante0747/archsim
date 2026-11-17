package io.archsim.tools.changes;

/**
 * A file a change touched, and whether the change deleted it. A rename deletes its old
 * path and adds its new one.
 *
 * @param path the file, from the repository's root
 * @param deleted whether the change deleted it
 */
record ChangedFile(String path, boolean deleted) {

	/**
	 * How the {@code changes} job's log names it: its path, marked when the change
	 * deleted it.
	 * @return the name
	 */
	String label() {
		return this.deleted ? "deleted: " + this.path : this.path;
	}

}
