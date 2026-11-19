package io.archsim.model.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Reads the files ArchSim ships in this package's resources: the price snapshots and
 * their index, and the whole-core families.
 */
final class ShippedFiles {

	private ShippedFiles() {
	}

	/**
	 * The content of a file this package ships.
	 * @param path the file, from this package's resources, such as
	 * {@code snapshots/index}
	 * @return its bytes
	 * @throws IllegalStateException when the file is missing from the jar
	 */
	static byte[] read(String path) {
		try (InputStream input = ShippedFiles.class.getResourceAsStream(path)) {
			if (input == null) {
				throw new IllegalStateException(path + " is missing from the model's pricing resources");
			}
			return input.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
