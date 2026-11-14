package io.archsim.tools.prices;

import java.util.Comparator;
import java.util.List;

import io.archsim.engine.text.PythonStrings;

/**
 * Text in Python's order ({@link PythonStrings#compare}): by code point, where Java's
 * {@link String#compareTo} compares UTF-16 units and so puts a character beyond U+FFFF
 * before U+E000 to U+FFFF.
 */
final class CodePoints {

	/** Strings by code point. */
	static final Comparator<String> ORDER = PythonStrings::compare;

	/** Lists of strings, item by item, a list before the longer lists it begins. */
	static final Comparator<List<String>> LISTS = CodePoints::compare;

	private CodePoints() {
	}

	private static int compare(List<String> left, List<String> right) {
		int shorter = Math.min(left.size(), right.size());
		for (int index = 0; index < shorter; index++) {
			int order = PythonStrings.compare(left.get(index), right.get(index));
			if (order != 0) {
				return order;
			}
		}
		return Integer.compare(left.size(), right.size());
	}

}
