package io.archsim.model.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes records as JSON the way the Python engine's pydantic models do, so the
 * design document's contract and its error messages stay the same (ADR-0022).
 *
 * <p>
 * A record declares its JSON with annotations on its components: {@link Default}, bounds
 * such as {@link Ge} and {@link Le}, {@link Matches}, {@link MaxItems} and so on, and
 * {@code @Nullable} for {@code X | None}. Reading an input checks all of it and reports
 * every error at once, with pydantic's error types, locations and messages, before any
 * record is made; a record's compact constructor then checks what spans its fields.
 * Writing gives the JSON pydantic's {@code
 * model_dump(mode="json", by_alias=True)} gives.
 *
 * <p>
 * Jackson parses and prints the JSON text; this class maps its trees to records.
 */
public final class RecordCodec {

	private RecordCodec() {
	}

	/**
	 * The record (or {@link Tagged} interface) {@code json} describes.
	 * @param <T> the type to read
	 * @param json the JSON input
	 * @param type the record or the tagged interface
	 * @return the record the JSON describes, its compact constructor's checks passed
	 * @throws BindingException with every error, when the JSON does not fit
	 */
	public static <T> T read(JsonNode json, Class<T> type) {
		Errors errors = new Errors();
		Object value = Shapes.of(type).read(json, Location.ROOT, errors);
		if (!errors.isEmpty() || value == null) {
			throw new BindingException(errors.list());
		}
		return type.cast(value);
	}

	/**
	 * The JSON of a record (or a member of a {@link Tagged} interface).
	 * @param value the record
	 * @return its JSON object, with the tag first for a member of a tagged interface
	 */
	public static ObjectNode write(Record value) {
		Class<?> type = value.getClass();
		for (Class<?> parent : type.getInterfaces()) {
			if (parent.isAnnotationPresent(Tagged.class)) {
				return (ObjectNode) Shapes.of(parent).write(value);
			}
		}
		return (ObjectNode) Shapes.of(type).write(value);
	}

	/**
	 * The JSON Schema of a record, as pydantic's {@code model_json_schema(by_alias=True)}
	 * writes it: the record's own definition with every record it uses under
	 * {@code $defs}.
	 * @param type the record
	 * @return its JSON Schema
	 */
	public static ObjectNode jsonSchema(Class<? extends Record> type) {
		Definitions definitions = new Definitions();
		ObjectNode schema = Shapes.record(type).definition(definitions);
		if (!definitions.isEmpty()) {
			schema.set("$defs", definitions.toJson());
		}
		return schema;
	}

}
