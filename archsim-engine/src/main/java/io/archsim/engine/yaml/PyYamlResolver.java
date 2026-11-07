package io.archsim.engine.yaml;

import java.util.regex.Pattern;

import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * What a plain scalar is, decided by PyYAML's implicit resolvers (its
 * {@code resolver.py}), copied pattern for pattern and in its order, so a scalar gets the
 * type the Python engine gave it: SnakeYAML's own read {@code 1e5} as a number, where
 * PyYAML's floats need a point and a signed exponent. Its {@code =} (value) resolver is
 * kept: its safe loader cannot build that type, so a value written {@code =} is refused,
 * as PyYAML refuses it, while a key written so stays text.
 */
final class PyYamlResolver extends Resolver {

	private static final Pattern BOOL = Pattern
		.compile("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$");

	private static final Pattern FLOAT = Pattern.compile("^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?"
			+ "|\\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?" + "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*"
			+ "|[-+]?\\.(?:inf|Inf|INF)" + "|\\.(?:nan|NaN|NAN))$");

	private static final Pattern INT = Pattern.compile("^(?:[-+]?0b[0-1_]+" + "|[-+]?0[0-7_]+"
			+ "|[-+]?(?:0|[1-9][0-9_]*)" + "|[-+]?0x[0-9a-fA-F_]+" + "|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$");

	private static final Pattern MERGE = Pattern.compile("^(?:<<)$");

	private static final Pattern NULL = Pattern.compile("^(?:~|null|Null|NULL|)$");

	private static final Pattern TIMESTAMP = Pattern.compile("^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"
			+ "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9]"
			+ "(?:\\.[0-9]*)?(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$");

	private static final Pattern VALUE = Pattern.compile("^(?:=)$");

	/** The first character SnakeYAML looks an empty scalar's resolvers up by. */
	private static final String EMPTY = "\0";

	@Override
	protected void addImplicitResolvers() {
		addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
		addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
		addImplicitResolver(Tag.INT, INT, "-+0123456789");
		addImplicitResolver(Tag.MERGE, MERGE, "<");
		addImplicitResolver(Tag.NULL, NULL, "~nN" + EMPTY);
		addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789");
		addImplicitResolver(PyYamlConstructor.VALUE, VALUE, "=");
	}

}
