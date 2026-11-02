package io.archsim.model.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * JSON text exactly as Python's {@code json.dumps(value, sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)} writes it: keys sorted by code point, no spaces, floats as their repr,
 * non-ASCII characters as they are. Hashes of it (a price snapshot's {@code sha256}) then match
 * the Python engine's.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(JsonNode value, StringBuilder out) {
        if (value.isNull() || value.isMissingNode()) {
            out.append("null");
        } else if (value.isBoolean()) {
            out.append(value.booleanValue());
        } else if (value.isIntegralNumber()) {
            out.append(value.bigIntegerValue());
        } else if (value.isNumber()) {
            out.append(number(value.doubleValue()));
        } else if (value.isString()) {
            string(value.stringValue(), out);
        } else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(value.get(i), out);
            }
            out.append(']');
        } else {
            List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(value.properties());
            properties.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
            out.append('{');
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(properties.get(i).getKey(), out);
                out.append(':');
                write(properties.get(i).getValue(), out);
            }
            out.append('}');
        }
    }

    /** A float as Python's json module writes it: its repr, and NaN and the infinities by name. */
    private static String number(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "Infinity" : "-Infinity";
        }
        return PythonText.repr(x);
    }

    /** A string as Python's json module writes it without ASCII escaping. */
    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Python's string order: by code point (Java's {@code compareTo} orders UTF-16 units). */
    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
