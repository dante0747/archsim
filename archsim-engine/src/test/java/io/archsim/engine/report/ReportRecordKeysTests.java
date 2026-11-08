package io.archsim.engine.report;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulator.SimulationCases;
import io.archsim.parity.GoldenFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every component of the report's records names a key a report writes where that record
 * is read (ADR-0039). The records skip the keys they do not name, so a component spelt
 * differently from its key would read as null or zero; this test fails instead. The
 * reports are the golden files' (each template's runs, as the frozen engine writes them)
 * and the Java simulator's of the designs {@code SimulationCasesTests} replays, which
 * write the Python engine's keys but for the provenance's runtime (ADR-0050).
 */
@DisplayName("The report's records name keys the frozen engine or the Java simulator writes")
class ReportRecordKeysTests {

	private static final List<JsonNode> JAVA_REPORTS = SimulationCases.recorded()
		.stream()
		.map((recorded) -> (JsonNode) SimulationCases.simulate(recorded))
		.toList();

	private static final Map<Class<?>, Set<String>> KEYS_WRITTEN = keysWritten(
			Stream.concat(goldenReports().stream(), JAVA_REPORTS.stream()).toList());

	@TestFactory
	Stream<DynamicTest> reportRecords_everyComponent_namesAKeyAReportWrites() {
		return KEYS_WRITTEN.entrySet()
			.stream()
			.map((written) -> DynamicTest.dynamicTest(written.getKey().getSimpleName(),
					() -> assertThat(written.getValue()).containsAll(componentNames(written.getKey()))));
	}

	@Test
	void reportRecords_inThePackage_areExactlyTheRecordsARunReportHolds() {
		List<Class<?>> records = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(RunReport.class.getPackageName())
			.stream()
			.map(JavaClass::reflect)
			.filter(Class::isRecord)
			.toList();

		assertThat(KEYS_WRITTEN.keySet()).containsExactlyInAnyOrderElementsOf(records);
	}

	@Test
	void provenance_ofAReportTheJavaSimulatorWrote_readsItsRuntimeAndDigest() {
		JsonNode written = JAVA_REPORTS.getFirst().get("provenance");

		ReportProvenance read = RunReport.read(JAVA_REPORTS.getFirst()).provenance();

		assertThat(List.of(Objects.requireNonNull(read.java()), read.resultDigest()))
			.containsExactly(written.get("java").stringValue(), written.get("resultDigest").stringValue());
	}

	private static List<String> componentNames(Class<?> record) {
		return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
	}

	private static List<JsonNode> goldenReports() {
		return GoldenFile.templates()
			.stream()
			.map(GoldenFile::read)
			.flatMap((file) -> file.root().get("runs").valueStream())
			.map((run) -> run.get("report"))
			.toList();
	}

	/**
	 * The keys the reports write where each record is read: every record a
	 * {@code RunReport} holds, each with the keys of every object read into it.
	 */
	private static Map<Class<?>, Set<String>> keysWritten(List<JsonNode> reports) {
		Map<Class<?>, Set<String>> keys = new LinkedHashMap<>();
		addRecords(RunReport.class, keys);
		reports.forEach((report) -> addKeys(RunReport.class, report, keys));
		return Collections.unmodifiableMap(keys);
	}

	/** Adds a record and every record it holds, each with no keys yet. */
	private static void addRecords(Class<?> record, Map<Class<?>, Set<String>> keys) {
		if (keys.putIfAbsent(record, new TreeSet<>()) != null) {
			return;
		}
		for (RecordComponent component : record.getRecordComponents()) {
			heldRecord(component.getGenericType()).ifPresent((held) -> addRecords(held, keys));
		}
	}

	/**
	 * Adds the keys of an object read into a record, and of the objects its components
	 * hold.
	 */
	private static void addKeys(Class<?> record, JsonNode json, Map<Class<?>, Set<String>> keys) {
		keys.computeIfAbsent(record, (type) -> new TreeSet<>()).addAll(json.propertyNames());
		for (RecordComponent component : record.getRecordComponents()) {
			Type type = component.getGenericType();
			heldRecord(type).ifPresent((held) -> objectsAt(type, json.path(component.getName()))
				.forEach((object) -> addKeys(held, object, keys)));
		}
	}

	/**
	 * The objects a value of a type holds: the value itself, or the items of a list or
	 * map, however deep.
	 */
	private static Stream<JsonNode> objectsAt(Type type, JsonNode value) {
		if (type instanceof ParameterizedType container) {
			return value.valueStream().flatMap((item) -> objectsAt(itemType(container), item));
		}
		return value.isObject() ? Stream.of(value) : Stream.empty();
	}

	/**
	 * The record a value of a type is read into: itself, or the items of a list or map.
	 */
	private static Optional<Class<?>> heldRecord(Type type) {
		if (type instanceof ParameterizedType container) {
			return heldRecord(itemType(container));
		}
		return (type instanceof Class<?> held && held.isRecord()) ? Optional.of(held) : Optional.empty();
	}

	/** What a list or map holds: its last type argument. */
	private static Type itemType(ParameterizedType container) {
		Type[] arguments = container.getActualTypeArguments();
		return arguments[arguments.length - 1];
	}

}
