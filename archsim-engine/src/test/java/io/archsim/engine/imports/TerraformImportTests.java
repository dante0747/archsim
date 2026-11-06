package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The Terraform importer where the Python's runtime raised an error the API answered
 * {@code 500} for: the Java refuses the text in Python's words. Everything else is
 * checked on the cases recorded from the frozen engine.
 */
class TerraformImportTests {

	private static final String LONG = "1".repeat(400);

	private final TerraformImport terraform = new TerraformImport(StandardComponents.registry());

	@Test
	void read_functionMemoryWhoseGibAreBeyondAFloat_isRefusedInPythonsWords() {
		String plan = plan("aws_lambda_function", "{\"function_name\": \"f\", \"memory_size\": " + LONG + "}");

		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> this.terraform.read(plan, null))
			.withMessage("integer division result too large for a float");
	}

	@Test
	void read_customMachineMemoryBeyondAFloat_isRefusedInPythonsWords() {
		String plan = plan("google_sql_database_instance",
				"{\"name\": \"s\", \"settings\": [{\"tier\": \"db-custom-2-" + LONG + "\"}]}");

		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> this.terraform.read(plan, null))
			.withMessage("integer division result too large for a float");
	}

	@Test
	void read_firstGenerationMachineBeyondAFloat_isRefusedInPythonsWords() {
		String plan = plan("google_sql_database_instance",
				"{\"name\": \"s\", \"settings\": [{\"tier\": \"db-n1-standard-" + LONG + "\"}]}");

		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> this.terraform.read(plan, null))
			.withMessage("int too large to convert to float");
	}

	@Test
	void read_storageWhoseGibAreBeyondAFloat_isRefusedInPythonsWords() {
		String plan = plan("azurerm_postgresql_flexible_server", "{\"name\": \"p\", \"storage_mb\": " + LONG + "}");

		assertThatExceptionOfType(ImportRefusedException.class).isThrownBy(() -> this.terraform.read(plan, null))
			.withMessage("integer division result too large for a float");
	}

	private static String plan(String type, String values) {
		return "{\"planned_values\": {\"root_module\": {\"resources\": [{\"address\": \"" + type + ".x\", \"type\": \""
				+ type + "\", \"name\": \"x\", \"values\": " + values + "}]}}}";
	}

}
