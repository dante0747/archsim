package io.archsim.model.codec;

import java.lang.reflect.AnnotatedType;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code str}: only a JSON string, with its length counted in code points as
 * Python does. With a {@link Const} it is a {@code Literal} of that one string.
 */
final class StringShape implements Shape {

	private final @Nullable String constant;

	private final @Nullable Integer minLength;

	private final @Nullable Integer maxLength;

	private final @Nullable String pattern;

	private final @Nullable Pattern regex;

	StringShape(@Nullable String constant, @Nullable Integer minLength, @Nullable Integer maxLength,
			@Nullable String pattern) {
		this.constant = constant;
		this.minLength = minLength;
		this.maxLength = maxLength;
		this.pattern = pattern;
		this.regex = (pattern != null) ? compile(pattern) : null;
	}

	/**
	 * The shape of a string type, from its {@link Const}, {@link MinLength},
	 * {@link MaxLength} and {@link Matches}.
	 * @param annotated the string type, with its annotations
	 * @return the string's shape
	 */
	static StringShape of(AnnotatedType annotated) {
		MinLength min = annotated.getAnnotation(MinLength.class);
		MaxLength max = annotated.getAnnotation(MaxLength.class);
		Matches pattern = annotated.getAnnotation(Matches.class);
		Const constant = annotated.getAnnotation(Const.class);
		return new StringShape((constant != null) ? constant.value() : null, (min != null) ? min.value() : null,
				(max != null) ? max.value() : null, (pattern != null) ? pattern.value() : null);
	}

	/**
	 * pydantic's Rust regex engine, in Java: {@code \d} and {@code \w} are Unicode, and a
	 * final {@code $} is the very end of the text (Java's would also match before a final
	 * newline).
	 * @param pattern the expression as the Python model spells it
	 * @return the expression compiled to match as pydantic's does
	 */
	private static Pattern compile(String pattern) {
		String java = (pattern.endsWith("$") && !pattern.endsWith("\\$"))
				? pattern.substring(0, pattern.length() - 1) + "\\z" : pattern;
		return Pattern.compile(java, Pattern.UNICODE_CHARACTER_CLASS);
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (this.constant != null) {
			return readConstant(this.constant, in, at, errors);
		}
		if (!in.isString()) {
			errors.add("string_type", at, Messages.STRING_TYPE);
			return null;
		}
		String value = in.stringValue();
		check(value, at, errors);
		return value;
	}

	private static String readConstant(String constant, JsonNode in, Location at, Errors errors) {
		if (!in.isString() || !in.stringValue().equals(constant)) {
			errors.add("literal_error", at, Messages.literal(Messages.quoted(List.of(constant))));
		}
		return constant;
	}

	/**
	 * Adds an error for the first constraint a string breaks: its length, then its
	 * pattern.
	 * @param value the string read
	 * @param at where it is in the input
	 * @param errors where the error is added
	 */
	private void check(String value, Location at, Errors errors) {
		int length = value.codePointCount(0, value.length());
		if (this.minLength != null && length < this.minLength) {
			errors.add("string_too_short", at, Messages.stringTooShort(this.minLength));
		}
		else if (this.maxLength != null && length > this.maxLength) {
			errors.add("string_too_long", at, Messages.stringTooLong(this.maxLength));
		}
		else if (this.pattern != null && this.regex != null && !this.regex.matcher(value).find()) {
			errors.add("string_pattern_mismatch", at, Messages.patternMismatch(this.pattern));
		}
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return NODES.stringNode((String) Objects.requireNonNull(value));
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		if (this.constant != null) {
			schema.put("const", this.constant);
		}
		schema.put("type", "string");
		if (this.minLength != null) {
			schema.put("minLength", this.minLength);
		}
		if (this.maxLength != null) {
			schema.put("maxLength", this.maxLength);
		}
		if (this.pattern != null) {
			schema.put("pattern", this.pattern);
		}
		return schema;
	}

}
