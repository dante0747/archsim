package io.archsim.cli;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import io.archsim.engine.check.FailOn;
import io.archsim.engine.export.ExportFormat;
import io.archsim.engine.imports.ImportFormat;
import io.archsim.model.codec.JsonEnum;

/**
 * The choices an argument takes by name, as argparse's {@code choices} took them: a
 * converter reads a name into its constant, refuses any other as a usage error, and lists
 * the names for the help.
 *
 * @param <T> the constants named
 */
abstract class Choices<T extends Enum<T> & JsonEnum> implements ITypeConverter<T>, Iterable<String> {

	private final Class<T> type;

	Choices(Class<T> type) {
		this.type = type;
	}

	@Override
	public T convert(String name) {
		return Arrays.stream(this.type.getEnumConstants())
			.filter((constant) -> constant.json().equals(name))
			.findFirst()
			.orElseThrow(() -> new TypeConversionException(
					"invalid choice: '%s' (choose from %s)".formatted(name, String.join(", ", names()))));
	}

	@Override
	public Iterator<String> iterator() {
		return names().iterator();
	}

	private List<String> names() {
		return Arrays.stream(this.type.getEnumConstants()).map(JsonEnum::json).toList();
	}

	/** What a design is exported as: {@code compose}, {@code k8s} or {@code mermaid}. */
	static final class ExportFormats extends Choices<ExportFormat> {

		ExportFormats() {
			super(ExportFormat.class);
		}

	}

	/**
	 * What a design is imported from: {@code compose}, {@code k8s} or {@code terraform}.
	 */
	static final class ImportFormats extends Choices<ImportFormat> {

		ImportFormats() {
			super(ImportFormat.class);
		}

	}

	/** What fails a check: {@code objectives} or {@code regression}. */
	static final class FailOns extends Choices<FailOn> {

		FailOns() {
			super(FailOn.class);
		}

	}

	/** What a check writes its result as: {@code markdown} or {@code json}. */
	static final class CheckFormats extends Choices<CheckFormat> {

		CheckFormats() {
			super(CheckFormat.class);
		}

	}

}
