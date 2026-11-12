package io.archsim.model.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonText;

/**
 * A pydantic model with {@code extra="forbid"}, as a Java record: a JSON object of its
 * fields.
 *
 * <p>
 * A key pydantic cannot encode to UTF-8 (one holding half of a surrogate pair) refuses
 * the whole record, with one {@code string_unicode} at the record and nothing else.
 * Otherwise, reading checks the fields in declaration order, each looked up by its JSON
 * name and then by its Python name, and reports every unknown key after them, in the
 * input's order. When all of it is valid it calls the canonical constructor; an
 * {@link IllegalArgumentException} from there is the record's own check failing (a
 * pydantic model validator), reported as a {@code value_error} at the object.
 */
final class RecordShape implements Shape {

	private final List<Field> fields;

	private final String name;

	private final @Nullable String description;

	private final @Nullable TagField tag;

	private final Constructor<?> constructor;

	/**
	 * Builds the shape of a record from its components and their annotations.
	 * @param type the record
	 * @param tag the record's tag in a {@link Tagged} union, or {@code null} outside one
	 */
	RecordShape(Class<?> type, @Nullable TagField tag) {
		RecordComponent[] components = type.getRecordComponents();
		if (components == null) {
			throw new IllegalArgumentException(type + " is not a record");
		}
		this.fields = Arrays.stream(components).map(Field::of).toList();
		Description declared = type.getAnnotation(Description.class);
		this.name = modelName(type);
		this.description = (declared != null) ? declared.value() : null;
		this.tag = tag;
		this.constructor = Reflection.canonicalConstructor(type, components);
	}

	/**
	 * The model's name, as messages and the JSON Schema use it.
	 * @return the {@link ModelName}, or else the record's simple name
	 */
	String name() {
		return this.name;
	}

	List<Field> fields() {
		return this.fields;
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isObject()) {
			errors.add("model_type", at, Messages.modelType(this.name));
			return null;
		}
		if (in.propertyNames().stream().anyMatch(Utf8Encoding::cannotEncode)) {
			// pydantic encodes every key to find the unknown ones, and gives up on the
			// record at the first it cannot encode, whatever else it found
			Utf8Encoding.refuse(at, errors);
			return null;
		}
		int mark = errors.mark();
		readTag(in, at, errors);
		@Nullable Object[] values = readFields(in, at, errors);
		reportExtras(in, at, errors);
		if (errors.since(mark)) {
			return null;
		}
		return construct(values, at, errors);
	}

	/**
	 * Adds an error when the input gives the tag property a value other than the
	 * record's.
	 * @param in the JSON object read
	 * @param at where the object is in the input
	 * @param errors where the error is added
	 */
	private void readTag(JsonNode in, Location at, Errors errors) {
		if (this.tag == null || !in.has(this.tag.property())) {
			return;
		}
		JsonNode value = in.get(this.tag.property());
		if (!value.isString() || !value.stringValue().equals(this.tag.value())) {
			errors.add("literal_error", at.child(this.tag.property()),
					Messages.literal(List.of(PythonText.repr(this.tag.value()))));
		}
	}

	/**
	 * The value of each field, in declaration order: read, defaulted or missing.
	 * @param in the JSON object read
	 * @param at where the object is in the input
	 * @param errors where the errors in the fields are added
	 * @return the values, {@code null} where a field is missing
	 */
	private @Nullable Object[] readFields(JsonNode in, Location at, Errors errors) {
		@Nullable Object[] values = new @Nullable Object[this.fields.size()];
		for (int i = 0; i < this.fields.size(); i++) {
			values[i] = readField(this.fields.get(i), in, at, errors);
		}
		return values;
	}

	private static @Nullable Object readField(Field field, JsonNode in, Location at, Errors errors) {
		String key = field.keyIn(in);
		if (key != null) {
			return field.shape().read(in.get(key), at.child(key), errors);
		}
		if (field.fieldDefault().required()) {
			errors.add("missing", at.child(field.name()), Messages.MISSING);
			return null;
		}
		Object fallback = field.fieldDefault().value();
		return (fallback instanceof JsonNode node) ? node.deepCopy() : fallback;
	}

	/**
	 * Adds an error for every key of the input that is neither the tag nor a field's.
	 * @param in the JSON object read
	 * @param at where the object is in the input
	 * @param errors where the errors are added, in the input's order
	 */
	private void reportExtras(JsonNode in, Location at, Errors errors) {
		Set<String> known = knownKeys(in);
		for (Map.Entry<String, JsonNode> property : in.properties()) {
			if (!known.contains(property.getKey())) {
				errors.add("extra_forbidden", at.child(property.getKey()), Messages.EXTRA_FORBIDDEN);
			}
		}
	}

	/**
	 * The keys of the input that the tag and the fields are read from.
	 * @param in the JSON object read
	 * @return the keys this record knows
	 */
	private Set<String> knownKeys(JsonNode in) {
		Set<String> known = new HashSet<>();
		if (this.tag != null) {
			known.add(this.tag.property());
		}
		for (Field field : this.fields) {
			String key = field.keyIn(in);
			if (key != null) {
				known.add(key);
			}
		}
		return known;
	}

	/**
	 * A record of these field values; a value error when the record rejects them.
	 * @param values the field values, in declaration order
	 * @param at where the object is in the input
	 * @param errors where a rejection is added
	 * @return the record, or {@code null} when it rejects the values
	 */
	@Nullable Object construct(@Nullable Object[] values, Location at, Errors errors) {
		try {
			return this.constructor.newInstance(values);
		}
		catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof IllegalArgumentException rejected && rejected.getMessage() != null) {
				errors.add("value_error", at, Messages.valueError(rejected.getMessage()));
				return null;
			}
			throw new IllegalStateException("record " + this.name + " failed to construct", ex.getCause());
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("record " + this.name + " cannot be constructed", ex);
		}
	}

	@Override
	public JsonNode write(@Nullable Object record) {
		Objects.requireNonNull(record);
		ObjectNode object = NODES.objectNode();
		if (this.tag != null) {
			object.put(this.tag.property(), this.tag.value());
		}
		for (Field field : this.fields) {
			Object value = Reflection.get(field.accessor(), record);
			if (field.omitWhenDefault() && Objects.equals(value, field.fieldDefault().value())) {
				continue;
			}
			object.set(field.name(), field.shape().write(value));
		}
		return object;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		return definitions.reference(this);
	}

	/**
	 * The record's JSON Schema definition, as pydantic writes a model's.
	 * @param definitions where the records the fields use are defined
	 * @return the definition, for {@code $defs} or as a whole schema
	 */
	ObjectNode definition(Definitions definitions) {
		ObjectNode definition = NODES.objectNode().put("additionalProperties", false);
		if (this.description != null) {
			definition.put("description", this.description);
		}
		ObjectNode properties = definition.putObject("properties");
		if (this.tag != null) {
			properties.putObject(this.tag.property())
				.put("const", this.tag.value())
				.put("default", this.tag.value())
				.put("title", PythonText.title(this.tag.property()))
				.put("type", "string");
		}
		ArrayNode required = NODES.arrayNode();
		for (Field field : this.fields) {
			properties.set(field.name(), property(field, definitions));
			if (field.fieldDefault().required()) {
				required.add(field.name());
			}
		}
		if (!required.isEmpty()) {
			definition.set("required", required);
		}
		return definition.put("title", this.name).put("type", "object");
	}

	/**
	 * A field's schema: its value's, with the title, description and default pydantic
	 * adds, and the field's {@link Ui} hints as {@code x-ui}.
	 * @param field the field
	 * @param definitions where the records the field uses are defined
	 * @return the field's property schema
	 */
	private static ObjectNode property(Field field, Definitions definitions) {
		ObjectNode schema = field.shape().schema(definitions);
		if (!isReference(field.shape())) {
			schema.put("title", PythonText.title(field.name()));
		}
		if (field.description() != null) {
			schema.put("description", field.description());
		}
		if (field.fieldDefault().inSchema()) {
			schema.set("default", field.shape().write(field.fieldDefault().value()));
		}
		if (field.uiHints() != null) {
			schema.set("x-ui", field.uiHints().deepCopy());
		}
		return schema;
	}

	/**
	 * Whether a field's schema is a {@code $ref} (pydantic gives those no title).
	 * @param shape the field's shape
	 * @return whether it is a record's, or a nullable record's
	 */
	private static boolean isReference(Shape shape) {
		if (shape instanceof NullableShape nullable) {
			return nullable.inner() instanceof RecordShape;
		}
		return shape instanceof RecordShape;
	}

	private static String modelName(Class<?> type) {
		ModelName name = type.getAnnotation(ModelName.class);
		return (name != null) ? name.value() : type.getSimpleName();
	}

	/**
	 * The tag of a record in a {@link Tagged} union: the property and the record's value.
	 *
	 * @param property the property that holds the tag, such as {@code kind}
	 * @param value the tag that selects this record, such as {@code lognormal}
	 */
	record TagField(String property, String value) {
	}

}
