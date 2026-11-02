package io.archsim.model.codec;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code str}: only a JSON string, with its length counted in code points as Python does.
 * With a {@link Const} it is a {@code Literal} of that one string.
 */
final class StringShape implements Shape {

    private final @Nullable String constant;
    private final @Nullable Integer minLength;
    private final @Nullable Integer maxLength;
    private final @Nullable String pattern;
    private final @Nullable Pattern regex;

    StringShape(
            @Nullable String constant,
            @Nullable Integer minLength,
            @Nullable Integer maxLength,
            @Nullable String pattern) {
        this.constant = constant;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.regex = pattern == null ? null : compile(pattern);
    }

    /**
     * pydantic's Rust regex engine, in Java: {@code \d} and {@code \w} are Unicode, and a final
     * {@code $} is the very end of the text (Java's would also match before a final newline).
     */
    private static Pattern compile(String pattern) {
        String java = pattern.endsWith("$") && !pattern.endsWith("\\$")
                ? pattern.substring(0, pattern.length() - 1) + "\\z"
                : pattern;
        return Pattern.compile(java, Pattern.UNICODE_CHARACTER_CLASS);
    }

    @Override
    public @Nullable Object read(JsonNode in, Location at, Errors errors) {
        if (constant != null) {
            if (!in.isString() || !in.stringValue().equals(constant)) {
                errors.add("literal_error", at, Messages.literal(Messages.quoted(List.of(constant))));
            }
            return constant;
        }
        if (!in.isString()) {
            errors.add("string_type", at, Messages.STRING_TYPE);
            return null;
        }
        String value = in.stringValue();
        int length = value.codePointCount(0, value.length());
        if (minLength != null && length < minLength) {
            errors.add("string_too_short", at, Messages.stringTooShort(minLength));
        } else if (maxLength != null && length > maxLength) {
            errors.add("string_too_long", at, Messages.stringTooLong(maxLength));
        } else if (pattern != null && regex != null && !regex.matcher(value).find()) {
            errors.add("string_pattern_mismatch", at, Messages.patternMismatch(pattern));
        }
        return value;
    }

    @Override
    public JsonNode write(@Nullable Object value) {
        return NODES.stringNode((String) Objects.requireNonNull(value));
    }

    @Override
    public ObjectNode schema(Definitions definitions) {
        ObjectNode schema = NODES.objectNode();
        if (constant != null) {
            schema.put("const", constant);
        }
        schema.put("type", "string");
        if (minLength != null) {
            schema.put("minLength", minLength);
        }
        if (maxLength != null) {
            schema.put("maxLength", maxLength);
        }
        if (pattern != null) {
            schema.put("pattern", pattern);
        }
        return schema;
    }
}
