package io.archsim.model.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads price snapshot files: the {@code *.json} files of extra folders, and the
 * snapshots ArchSim ships in this package's {@code snapshots/} resources, which the build
 * lists in {@code snapshots/index}.
 */
final class SnapshotFiles {

	/**
	 * The resource, beside the shipped snapshots, that lists their file names one per
	 * line in file order: the build writes it.
	 */
	private static final String INDEX = "index";

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
	 * @return the shipped snapshots, in file order
	 * @throws IllegalStateException when the index or a snapshot is missing from the jar
	 */
	static List<FoundSnapshot> shipped() {
		return shippedNames().stream()
			.map((name) -> new FoundSnapshot(PriceSnapshot.parse(JSON.readTree(resource(name))), name, true))
			.toList();
	}

	/**
	 * The file names of the snapshots ArchSim ships, as the build listed them.
	 * @return the names, in file order
	 * @throws IllegalStateException when the index is missing from the jar
	 */
	static List<String> shippedNames() {
		return new String(resource(INDEX), StandardCharsets.UTF_8).lines().toList();
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

	// a file of the shipped snapshots' resources
	private static byte[] resource(String name) {
		try (InputStream input = SnapshotFiles.class.getResourceAsStream("snapshots/" + name)) {
			if (input == null) {
				throw new IllegalStateException("the shipped snapshots' " + name + " is missing from the jar");
			}
			return input.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
