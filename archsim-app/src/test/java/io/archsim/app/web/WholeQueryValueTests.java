package io.archsim.app.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.format.support.DefaultFormattingConversionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text bound to a {@code List<String>} through Spring MVC's conversion service, with
 * {@link WholeQueryValue} added as {@code WebConfiguration} adds it: one value stays
 * whole, as FastAPI read a query value.
 */
class WholeQueryValueTests {

	private static final TypeDescriptor STRINGS = TypeDescriptor.collection(List.class,
			TypeDescriptor.valueOf(String.class));

	private static final TypeDescriptor INTEGERS = TypeDescriptor.collection(List.class,
			TypeDescriptor.valueOf(Integer.class));

	private final DefaultFormattingConversionService conversions = conversions();

	@Test
	void convert_oneValueWithACommaToStrings_isTheWholeValue() {
		assertThat(this.conversions.convert("Acme, Inc", TypeDescriptor.valueOf(String.class), STRINGS))
			.isEqualTo(List.of("Acme, Inc"));
	}

	@Test
	void convert_emptyValueToStrings_isOneEmptyValue() {
		assertThat(this.conversions.convert("", TypeDescriptor.valueOf(String.class), STRINGS)).isEqualTo(List.of(""));
	}

	@Test
	void convert_valuesGivenTwiceToStrings_areBothValues() {
		assertThat(
				this.conversions.convert(new String[] { "a,b", "c" }, TypeDescriptor.valueOf(String[].class), STRINGS))
			.isEqualTo(List.of("a,b", "c"));
	}

	@Test
	void convert_oneValueToIntegers_isSplitAsSpringSplitsIt() {
		assertThat(this.conversions.convert("1,2", TypeDescriptor.valueOf(String.class), INTEGERS))
			.isEqualTo(List.of(1, 2));
	}

	private static DefaultFormattingConversionService conversions() {
		DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();
		conversions.addConverter(new WholeQueryValue());
		return conversions;
	}

}
