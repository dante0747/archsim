package io.archsim.model.codec;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * One field of a record: its JSON name, the snake_case name pydantic also accepts, how its value
 * maps to JSON, and its default.
 *
 * @param name the JSON name, the record component's name ({@code readRatio})
 * @param pythonName the Python attribute name, accepted too ({@code read_ratio})
 * @param shape how the value maps to JSON
 * @param accessor the record's accessor of the field
 * @param required whether the input must have the field
 * @param defaultValue the value when the input leaves the field out
 * @param defaultInSchema whether the JSON Schema shows the default (not for objects and arrays)
 * @param omitWhenDefault whether the written JSON leaves the field out while it has its default
 * @param description the field's description in the JSON Schema
 * @param extra more JSON Schema for the field
 */
record Field(
        String name,
        String pythonName,
        Shape shape,
        Method accessor,
        boolean required,
        @Nullable Object defaultValue,
        boolean defaultInSchema,
        boolean omitWhenDefault,
        @Nullable String description,
        @Nullable ObjectNode extra) {

    /** {@code readRatio} becomes {@code read_ratio}, as pydantic's alias generator undone. */
    static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
