package io.archsim.model.pricing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** The files ArchSim ships in the pricing package's resources. */
class ShippedFilesTests {

	/** The pricing package's resources, in the model's sources. */
	private static final Path RESOURCES = Repository.root()
		.resolve("archsim-model/src/main/resources/io/archsim/model/pricing");

	@Test
	void read_aFileThePackageShips_isItsContent() throws IOException {
		byte[] source = Files.readAllBytes(RESOURCES.resolve("whole-core-families.json"));

		assertThat(ShippedFiles.read("whole-core-families.json")).isEqualTo(source);
	}

	@Test
	void read_aFileThePackageDoesNotShip_saysWhichIsMissing() {
		assertThatIllegalStateException().isThrownBy(() -> ShippedFiles.read("snapshots/no-such-file.json"))
			.withMessage("snapshots/no-such-file.json is missing from the model's pricing resources");
	}

}
