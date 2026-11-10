package io.archsim.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.component.standard.StandardComponents;

/**
 * {@code archsim catalog}: every component type, with its settings, ports, presets and
 * technologies, as JSON.
 */
@Command(name = "catalog", description = "print the component catalog as JSON", mixinStandardHelpOptions = true,
		version = Archsim.VERSION)
final class CatalogCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	/**
	 * Prints the catalog's component types.
	 * @return 0
	 */
	@Override
	public Integer call() {
		JsonOutput.print(this.spec.commandLine().getOut(),
				ComponentCatalog.of(StandardComponents.registry()).get("components"));
		return 0;
	}

}
