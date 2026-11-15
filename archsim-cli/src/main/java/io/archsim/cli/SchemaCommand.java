package io.archsim.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import io.archsim.model.DesignDocuments;

/**
 * {@code archsim schema}: the JSON Schema of the design document.
 */
@Command(name = "schema", description = "print the document JSON Schema", mixinStandardHelpOptions = true,
		versionProvider = VersionProvider.class)
final class SchemaCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	/**
	 * Prints the schema.
	 * @return 0
	 */
	@Override
	public Integer call() {
		JsonOutput.print(this.spec.commandLine().getOut(), DesignDocuments.jsonSchema());
		return 0;
	}

}
