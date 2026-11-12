package io.archsim.app.web;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import io.archsim.model.codec.IgnoreUnknownKeys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request body the API reads into a record ignores its unknown keys, as FastAPI's
 * request models did (pydantic's default, {@code extra="ignore"}; ADR-0034): a client
 * that sends a property the API does not know is answered as if it had not.
 */
class RequestBodiesTests {

	private static final JavaClasses APP = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("io.archsim.app");

	@Test
	void apiBodies_inTheControllers_areFound() {
		assertThat(apiBodyRecords()).isNotEmpty();
	}

	@Test
	void apiBodies_everyRecord_ignoresUnknownKeys() {
		List<String> unmarked = apiBodyRecords().stream()
			.filter((body) -> !body.isAnnotatedWith(IgnoreUnknownKeys.class))
			.map(JavaClass::getSimpleName)
			.toList();

		assertThat(unmarked).isEmpty();
	}

	/**
	 * A record held by a body would keep forbidding unknown keys, where FastAPI's nested
	 * request models ignored them; documents are read as JSON trees, and validated later.
	 */
	@Test
	void apiBodies_everyRecord_holdsNoRecord() {
		List<String> holding = apiBodyRecords().stream()
			.filter((body) -> body.getFields().stream().map(JavaField::getRawType).anyMatch(JavaClass::isRecord))
			.map(JavaClass::getSimpleName)
			.toList();

		assertThat(holding).isEmpty();
	}

	private static List<JavaClass> apiBodyRecords() {
		return APP.stream()
			.flatMap((type) -> type.getMethods().stream())
			.flatMap((method) -> method.getParameters().stream())
			.filter((parameter) -> parameter.isAnnotatedWith(ApiBody.class))
			.map(JavaParameter::getRawType)
			.filter(JavaClass::isRecord)
			.distinct()
			.toList();
	}

}
