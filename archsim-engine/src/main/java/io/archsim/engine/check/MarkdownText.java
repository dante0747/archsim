package io.archsim.engine.check;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.text.PythonStrings;

/**
 * Text that comes from the design (its name, a part's label, a note about it) put in a
 * pull-request comment so it cannot hide or fake the verdict ({@code _text},
 * {@code _code}): on one line, its HTML and Markdown escaped, or as inline code.
 */
final class MarkdownText {

	/** How Python's {@code str} writes a value that is not there. */
	static final String NONE = "None";

	/** The characters Markdown would read as markup, as {@code _text} escapes them. */
	private static final Pattern MARKUP = Pattern.compile("([\\\\`*_\\[\\]#|!~])");

	private MarkdownText() {
	}

	/**
	 * Design text as plain Markdown ({@code _text}): its whitespace runs one space, its
	 * HTML escaped as {@code html.escape(..., quote=False)} escapes it, and Markdown's
	 * markup characters after a backslash.
	 * @param value the text; {@code null} reads {@code None}, as Python's {@code str}
	 * @return the escaped text
	 */
	static String escaped(@Nullable String value) {
		String html = oneLine(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return MARKUP.matcher(html).replaceAll((match) -> Matcher.quoteReplacement("\\" + match.group(1)));
	}

	/**
	 * Design text as inline code ({@code _code}), literal on GitHub: on one line, its
	 * backticks made single quotes.
	 * @param value the text
	 * @return the code span
	 */
	static String code(String value) {
		return "`" + oneLine(value).replace('`', '\'') + "`";
	}

	/**
	 * A word with its first letter a capital and the rest small, as Python's
	 * {@code str.capitalize()} writes the words a check capitalizes ({@code high},
	 * {@code medium}, {@code low}): Java's simple case mappings, where Python's full ones
	 * differ for a few letters ({@code ß}).
	 * @param word the word
	 * @return the word, capitalized
	 */
	static String capitalized(String word) {
		if (word.isEmpty()) {
			return word;
		}
		int first = word.codePointAt(0);
		return new StringBuilder().appendCodePoint(Character.toTitleCase(first))
			.append(word.substring(Character.charCount(first)).toLowerCase(Locale.ROOT))
			.toString();
	}

	private static String oneLine(@Nullable String value) {
		return String.join(" ", PythonStrings.words((value != null) ? value : NONE));
	}

}
