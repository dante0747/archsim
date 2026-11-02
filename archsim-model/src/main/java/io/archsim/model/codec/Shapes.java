package io.archsim.model.codec;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the {@link Shape} of a Java type from its annotations, once per record and union.
 *
 * <p>The types a record may use: {@link String}, {@code double}, {@code int}, {@code long},
 * {@code boolean} (or their boxes), a {@link JsonEnum}, a {@link List} or a {@link Map} with
 * string keys of these, {@link JsonNode} (any JSON), {@link ObjectNode} (any JSON object),
 * another record, or a {@link Tagged} sealed interface; {@code @Nullable} on any of them allows
 * {@code null}.
 */
final class Shapes {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ClassValue<RecordShape> RECORDS = new ClassValue<>() {
        @Override
        protected RecordShape computeValue(Class<?> type) {
            return buildRecord(type, null);
        }
    };

    private static final ClassValue<Shape> UNIONS = new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> type) {
            return buildUnion(type);
        }
    };

    private Shapes() {}

    /** The shape of a record or a {@link Tagged} sealed interface, used as a whole input. */
    static Shape of(Class<?> type) {
        if (type.isAnnotationPresent(Tagged.class)) {
            return UNIONS.get(type);
        }
        if (type.isRecord()) {
            return type.isAnnotationPresent(AsArray.class) ? tuple(type) : RECORDS.get(type);
        }
        throw new IllegalArgumentException(type + " is neither a record nor a tagged union");
    }

    static RecordShape record(Class<?> type) {
        return RECORDS.get(type);
    }

    /** The shape of a field or list item, from its annotated type. */
    static Shape of(AnnotatedType annotated) {
        if (annotated.isAnnotationPresent(Nullable.class)) {
            return new NullableShape(plain(annotated));
        }
        return plain(annotated);
    }

    private static Shape plain(AnnotatedType annotated) {
        Class<?> raw = raw(annotated);
        if (raw == String.class) {
            MinLength min = annotated.getAnnotation(MinLength.class);
            MaxLength max = annotated.getAnnotation(MaxLength.class);
            Matches pattern = annotated.getAnnotation(Matches.class);
            Const constant = annotated.getAnnotation(Const.class);
            return new StringShape(
                    constant == null ? null : constant.value(),
                    min == null ? null : min.value(),
                    max == null ? null : max.value(),
                    pattern == null ? null : pattern.value());
        }
        if (raw == double.class || raw == Double.class) {
            return new FloatShape(Bounds.of(annotated));
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            OneOf oneOf = annotated.getAnnotation(OneOf.class);
            if (oneOf != null) {
                return IntChoiceShape.of(oneOf);
            }
            return new IntegerShape(Bounds.of(annotated), raw == long.class || raw == Long.class);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return new BooleanShape();
        }
        if (raw.isEnum() && JsonEnum.class.isAssignableFrom(raw)) {
            return new EnumShape(raw);
        }
        if (raw == List.class) {
            MinItems min = annotated.getAnnotation(MinItems.class);
            MaxItems max = annotated.getAnnotation(MaxItems.class);
            UniqueItems unique = annotated.getAnnotation(UniqueItems.class);
            return new ListShape(
                    of(typeArgument(annotated, 0)),
                    min == null ? null : min.value(),
                    max == null ? null : max.value(),
                    unique == null ? null : unique.value());
        }
        if (raw == Map.class) {
            if (raw(typeArgument(annotated, 0)) != String.class) {
                throw new IllegalArgumentException("a map's keys are strings: " + annotated);
            }
            return new MapShape(of(typeArgument(annotated, 1)));
        }
        if (raw == JsonNode.class) {
            return new AnyShape();
        }
        if (raw == ObjectNode.class) {
            return new ObjectAnyShape();
        }
        return of(raw);
    }

    private static RecordShape buildRecord(Class<?> type, RecordShape.@Nullable TagField tag) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new IllegalArgumentException(type + " is not a record");
        }
        List<Field> fields = new ArrayList<>();
        for (RecordComponent component : components) {
            fields.add(field(component));
        }
        Description description = type.getAnnotation(Description.class);
        return new RecordShape(
                modelName(type),
                description == null ? null : description.value(),
                tag,
                fields,
                constructor(type, components));
    }

    private static Field field(RecordComponent component) {
        AnnotatedType annotated = component.getAnnotatedType();
        Shape shape = of(annotated);
        Default declared = component.getAnnotation(Default.class);
        boolean nullable = shape instanceof NullableShape;
        boolean required = declared == null && !nullable;
        Object defaultValue = null;
        boolean defaultInSchema = nullable;
        if (declared != null) {
            JsonNode json = defaultJson(declared.value(), raw(annotated));
            Errors errors = new Errors();
            defaultValue = shape.read(json, Location.ROOT, errors);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("the default of " + component + " does not fit it: " + errors.list());
            }
            defaultInSchema = !json.isContainer();
        }
        Description description = component.getAnnotation(Description.class);
        SchemaExtra extra = component.getAnnotation(SchemaExtra.class);
        return new Field(
                component.getName(),
                Field.snakeCase(component.getName()),
                shape,
                component.getAccessor(),
                required,
                defaultValue,
                !required && defaultInSchema,
                component.isAnnotationPresent(OmitWhenDefault.class),
                description == null ? null : description.value(),
                extra == null ? null : (ObjectNode) JSON.readTree(extra.value()));
    }

    /** A default as JSON: the string itself for a string or an enum, else the annotation's JSON. */
    private static JsonNode defaultJson(String value, Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return JsonNodeFactory.instance.stringNode(value);
        }
        return JSON.readTree(value);
    }

    private static Shape buildUnion(Class<?> type) {
        Tagged tagged = type.getAnnotation(Tagged.class);
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (tagged == null || permitted == null) {
            throw new IllegalArgumentException(type + " is not a tagged sealed interface");
        }
        Map<String, RecordShape> members = new LinkedHashMap<>();
        Map<Class<?>, String> tags = new LinkedHashMap<>();
        for (Class<?> member : permitted) {
            Tag tag = member.getAnnotation(Tag.class);
            if (tag == null) {
                throw new IllegalArgumentException(member + " of " + type + " has no @Tag");
            }
            members.put(tag.value(), buildRecord(member, new RecordShape.TagField(tagged.value(), tag.value())));
            tags.put(member, tag.value());
        }
        return new UnionShape(tagged.value(), members, tags);
    }

    private static Shape tuple(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        return new TupleShape(
                Arrays.stream(components).map(c -> of(c.getAnnotatedType())).toList(),
                Arrays.stream(components).map(RecordComponent::getAccessor).toList(),
                constructor(type, components));
    }

    static String modelName(Class<?> type) {
        ModelName name = type.getAnnotation(ModelName.class);
        return name == null ? type.getSimpleName() : name.value();
    }

    private static Constructor<?> constructor(Class<?> type, RecordComponent[] components) {
        try {
            return type.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type + " has no canonical constructor", e);
        }
    }

    private static Class<?> raw(AnnotatedType annotated) {
        if (annotated.getType() instanceof Class<?> type) {
            return type;
        }
        if (annotated instanceof AnnotatedParameterizedType parameterized
                && parameterized.getType() instanceof ParameterizedType type
                && type.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new IllegalArgumentException("unsupported field type: " + annotated);
    }

    private static AnnotatedType typeArgument(AnnotatedType annotated, int index) {
        if (annotated instanceof AnnotatedParameterizedType parameterized) {
            return parameterized.getAnnotatedActualTypeArguments()[index];
        }
        throw new IllegalArgumentException("a raw collection type: " + annotated);
    }
}
