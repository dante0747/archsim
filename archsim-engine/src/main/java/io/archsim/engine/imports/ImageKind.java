package io.archsim.engine.imports;

import org.jspecify.annotations.Nullable;

/**
 * What a container image is taken for, and why, for the import report.
 *
 * @param use infrastructure (a part type and technology), a tool or an application
 * @param type the part type it becomes
 * @param technology its technology; {@code null} for a tool or an application
 * @param why why, in words
 */
record ImageKind(Use use, String type, @Nullable String technology, String why) {

	/**
	 * What an image is used as.
	 */
	enum Use {

		/** A technology's own image: a database, cache, queue or proxy. */
		INFRASTRUCTURE,

		/** Something that runs next to infrastructure: an exporter, a UI, a client. */
		TOOL,

		/** Anything else: an application, which becomes a service. */
		APPLICATION

	}

}
