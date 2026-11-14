package io.archsim.tools.prices;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonNumbers;

/**
 * A row of an AWS offer file, by its header's column names, and what the refresh asks of
 * every row: its price, whether it is an on-demand price of the region itself, its usage
 * type. A column the file does not have reads as Python's {@code None}, which no value
 * equals.
 *
 * @param columns the header's columns
 * @param values the row's values, one per column
 */
record OfferRow(Columns columns, List<String> values) {

	/** A usage type's region prefix: {@code USE1-}, {@code EU-}, {@code APS3-}. */
	private static final Pattern REGION_PREFIX = Pattern.compile("^(USE1|[A-Z]{2,3}\\d?)-(?=[A-Z])",
			Pattern.UNICODE_CHARACTER_CLASS);

	OfferRow {
		values = List.copyOf(values);
	}

	/**
	 * A column's value.
	 * @param column the column's name
	 * @return its value; {@code null} when the file has no such column
	 */
	@Nullable String get(String column) {
		Integer index = this.columns.indexes().get(column);
		return (index != null) ? this.values.get(index) : null;
	}

	/**
	 * A column's value, empty when the file has no such column.
	 * @param column the column's name
	 * @return its value
	 */
	String text(String column) {
		String value = get(column);
		return (value != null) ? value : "";
	}

	/**
	 * Whether a column holds one of these values; never when the file has no such column.
	 * @param column the column's name
	 * @param values the values
	 * @return whether it holds one
	 */
	boolean is(String column, String... values) {
		String value = get(column);
		return value != null && List.of(values).contains(value);
	}

	/**
	 * The price per unit, read as Python's {@code float()} reads it.
	 * @return the price; 0 when it is missing or not a number
	 */
	double price() {
		try {
			return PythonNumbers.parseFloat(text("PricePerUnit"));
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	/**
	 * Whether this is an on-demand price, not a reserved or savings plan's.
	 * @return whether it is on demand
	 */
	boolean isOnDemand() {
		return is("TermType", "OnDemand");
	}

	/**
	 * Whether this is a price for the region itself, not an Outposts rack, Local Zone or
	 * Wavelength Zone (their rows can sit in the region's file, at other prices), and not
	 * a previous generation's type.
	 * @return whether it is the region's own current price
	 */
	boolean isRegional() {
		String location = text("Location Type");
		return ("AWS Region".equals(location) || location.isEmpty()) && !"No".equals(text("Current Generation"));
	}

	/**
	 * The usage type without its region prefix ({@code USE1-}, {@code EU-}): us-east-1's
	 * rows usually have none, other regions' do. Infrequent Access's {@code IA-} is kept.
	 * @return the usage type
	 */
	String usage() {
		String usage = rawUsage();
		return usage.startsWith("IA-") ? usage : REGION_PREFIX.matcher(usage).replaceFirst("");
	}

	/**
	 * The usage type as the file gives it, region prefix and all: the {@code usageType}
	 * column, else the {@code Usage Type} one.
	 * @return the usage type; empty when the file has neither column
	 */
	String rawUsage() {
		return (get("usageType") != null) ? text("usageType") : text("Usage Type");
	}

	/**
	 * An offer file's header: each column's index by its name, the last of two with one
	 * name, as Python's {@code dict} kept it.
	 *
	 * @param indexes each column's index by its name
	 * @param size how many columns there are
	 */
	record Columns(Map<String, Integer> indexes, int size) {

		Columns {
			indexes = Map.copyOf(indexes);
		}

		/**
		 * The columns of a header row.
		 * @param names the column names, in order
		 * @return the columns
		 */
		static Columns of(List<String> names) {
			Map<String, Integer> indexes = new HashMap<>();
			for (int index = 0; index < names.size(); index++) {
				indexes.put(names.get(index), index);
			}
			return new Columns(indexes, names.size());
		}

	}

}
