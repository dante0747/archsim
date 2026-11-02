package io.archsim.model.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads price snapshot files: the {@code *.json} files of extra folders, and the
 * snapshots ArchSim ships in this package's {@code snapshots/} resources.
 */
final class SnapshotFiles {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SnapshotFiles() {
	}

	/**
	 * The snapshots in these folders, folder by folder, each folder's by file name. A
	 * folder that does not exist is skipped, and so is a file that is not a valid
	 * snapshot.
	 * @param folders the folders to read
	 * @return the snapshots found
	 * @throws UncheckedIOException when a folder cannot be listed
	 */
	static List<FoundSnapshot> inFolders(List<Path> folders) {
		List<FoundSnapshot> found = new ArrayList<>();
		for (Path folder : folders) {
			if (Files.isDirectory(folder)) {
				found.addAll(inFolder(folder));
			}
		}
		return found;
	}

	/**
	 * The snapshots ArchSim ships.
	 * @param names the file names of the shipped snapshots, in order
	 * @return the shipped snapshots, in the same order
	 * @throws IllegalStateException when one is missing from the jar
	 */
	static List<FoundSnapshot> shipped(List<String> names) {
		return names.stream()
			.map((name) -> new FoundSnapshot(PriceSnapshot.parse(resource(name)), name, true))
			.toList();
	}

	private static List<FoundSnapshot> inFolder(Path folder) {
		List<FoundSnapshot> found = new ArrayList<>();
		for (Path file : jsonFiles(folder)) {
			String name = file.getFileName().toString();
			read(file).ifPresent((snapshot) -> found.add(new FoundSnapshot(snapshot, name, false)));
		}
		return found;
	}

	private static List<Path> jsonFiles(Path folder) {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter((file) -> file.toString().endsWith(".json")).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot list " + folder, ex);
		}
	}

	private static Optional<PriceSnapshot> read(Path file) {
		try {
			return Optional.of(PriceSnapshot.parse(JSON.readTree(file.toFile())));
		}
		catch (PricingException | JacksonException ex) {
			// not a snapshot: not offered
			return Optional.empty();
		}
	}

	private static JsonNode resource(String name) {
		try (InputStream input = SnapshotFiles.class.getResourceAsStream("snapshots/" + name)) {
			if (input == null) {
				throw new IllegalStateException("the shipped snapshot " + name + " is missing from the jar");
			}
			return JSON.readTree(input);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
