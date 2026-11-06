package io.archsim.engine.export;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.text.PythonText;

/**
 * A part as the exports deploy it: its kind, its settings over its type's defaults, its
 * DNS name, and the technology it is deployed as.
 *
 * @param node the part
 * @param family its kind
 * @param settings its settings over its type's defaults, read leniently (ADR-0038)
 * @param name its id as a DNS name
 * @param technology the technology it is deployed as: its own, or its kind's default when
 * it names none or {@code generic}; {@code null} when there is none
 * @param spec how that technology is deployed; {@code null} when the exports have no
 * image for it
 * @param experimental whether its technology is experimental for its type
 */
record DeployPart(NodeSpec node, ComponentFamily family, LenientSettings settings, String name,
		@Nullable String technology, @Nullable TechnologySpec spec, boolean experimental) {

	/** What the exports name a part's technology when it names none. */
	static final String GENERIC = "generic";

	/** The port a part with no image listens on. */
	private static final int DEFAULT_PORT = 80;

	/**
	 * The part's id.
	 * @return its id
	 */
	String id() {
		return this.node.id();
	}

	/**
	 * How the part is deployed.
	 * @return its technology's spec; empty when the exports have no image for it
	 */
	Optional<TechnologySpec> deployment() {
		return Optional.ofNullable(this.spec);
	}

	/**
	 * Whether the part is a third party's service or a managed one: an address callers
	 * reach, not something the export deploys.
	 * @return {@code true} for a third-party API or a managed service
	 */
	boolean isReachedByAddress() {
		return this.family == ComponentFamily.EXTERNAL || isManaged();
	}

	/**
	 * Whether the part's technology is a managed service, which nothing deploys.
	 * @return {@code true} for a managed technology
	 */
	boolean isManaged() {
		return this.spec != null && this.spec.managed();
	}

	/**
	 * The URL a caller reaches the part at, its name as the host.
	 * @return the URL, with its technology's scheme and port
	 */
	String url() {
		String scheme = TechnologySpecs.scheme((this.technology != null) ? this.technology : "");
		int port = (this.spec != null) ? this.spec.port() : DEFAULT_PORT;
		return scheme + "://" + this.name + ":" + port;
	}

	/**
	 * The technology the part is listed with: the one it is deployed as, else its own
	 * setting in words, {@code generic} when that is empty too.
	 * @return the technology
	 */
	String listedTechnology() {
		return (this.technology != null && !this.technology.isEmpty()) ? this.technology : technologySetting();
	}

	/**
	 * The part's own technology setting in words.
	 * @return the setting as Python's {@code str(technology or "generic")} writes it
	 */
	String technologySetting() {
		return this.settings.truthy("technology", false) ? text("technology") : GENERIC;
	}

	/**
	 * The part's own technology, when it names one other than {@code generic}.
	 * @return the technology; empty otherwise
	 */
	Optional<String> runtime() {
		return this.settings.text("technology").filter((technology) -> !technology.equals(GENERIC));
	}

	/**
	 * A setting in words, whatever its kind.
	 * @param setting the setting's JSON name
	 * @return the setting as Python's {@code str()} writes it
	 */
	String text(String setting) {
		return PythonText.str(this.settings.value(setting));
	}

	/**
	 * A number setting.
	 * @param setting the setting's JSON name
	 * @param otherwise the number when it is not a finite number
	 * @return the setting, or {@code otherwise}
	 */
	double number(String setting, double otherwise) {
		return this.settings.number(setting).orElse(otherwise);
	}

	/**
	 * How many replicas the part runs: its minimum when it autoscales.
	 * @return at least one
	 */
	double replicas() {
		double replicas = number("replicas", 1);
		return Math.max(1, this.settings.isTrue("autoscaling") ? number("minReplicas", replicas) : replicas);
	}

	/**
	 * How many instances a data store or a queue runs: its shards times its copies.
	 * @return at least one
	 */
	double instances() {
		double shards = Math.max(1, number("shards", 1));
		return shards * Math.max(1 + number("readReplicas", 0), number("replicationFactor", 1));
	}

}
