package io.archsim.model;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;

/**
 * Where a design holds floats: every float component of every record the codec reads for
 * a design, found from the document's own records and from each component type's
 * settings, so a record or a field added later is found without being listed.
 */
final class FloatSlots {

	private FloatSlots() {
	}

	/**
	 * Every float of a design.
	 * @return the slots, the document's first, then each component type's settings
	 */
	static List<FloatSlot> ofADesign() {
		Set<Class<? extends Record>> records = new LinkedHashSet<>();
		collect(ArchitectureDocument.class, records);
		StandardComponents.registry().specs().forEach((spec) -> collect(spec.configType(), records));
		List<FloatSlot> slots = new ArrayList<>();
		records.forEach((holder) -> slots.addAll(of(holder)));
		return slots;
	}

	// the records a type holds, itself included: a record's components, a sealed
	// interface's records, a list's items and a map's values
	private static void collect(Type type, Set<Class<? extends Record>> records) {
		if (type instanceof ParameterizedType parameterized) {
			for (Type argument : parameterized.getActualTypeArguments()) {
				collectItem(argument, records);
			}
		}
		else if (type instanceof Class<?> raw && raw.isSealed()) {
			for (Class<?> permitted : raw.getPermittedSubclasses()) {
				collect(permitted, records);
			}
		}
		else if (type instanceof Class<?> raw && raw.isRecord()) {
			collectRecord(raw.asSubclass(Record.class), records);
		}
	}

	// a record and the records its components hold, each once
	private static void collectRecord(Class<? extends Record> record, Set<Class<? extends Record>> records) {
		if (records.contains(record)) {
			return;
		}
		records.add(record);
		for (RecordComponent component : record.getRecordComponents()) {
			collect(component.getGenericType(), records);
		}
	}

	// a float held in a list or a map has no slot here: none exists today, and one added
	// must not go unchecked
	private static void collectItem(Type item, Set<Class<? extends Record>> records) {
		if (isFloat(item)) {
			throw new IllegalStateException("a float in a collection is not checked: teach FloatSlots to reach it");
		}
		collect(item, records);
	}

	private static List<FloatSlot> of(Class<? extends Record> holder) {
		RecordComponent[] components = holder.getRecordComponents();
		List<FloatSlot> slots = new ArrayList<>();
		for (int index = 0; index < components.length; index++) {
			if (isFloat(components[index].getType())) {
				slots.add(new FloatSlot(holder, index));
			}
		}
		return slots;
	}

	private static boolean isFloat(Type type) {
		return type == double.class || type == Double.class;
	}

}
