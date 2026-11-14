package io.archsim.tools.prices;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;

/**
 * One instance type's on-demand list price in a snapshot, with its shape and, for a
 * database, cache or broker type, the engines it is sold for.
 *
 * @param sku the type, such as {@code m7g.large}, {@code db.t4g.micro} or
 * {@code Standard_D4s_v5}
 * @param family its family, such as {@code m7g} or {@code Dsv5}
 * @param shape its vCPUs and memory, the memory rounded to three decimals
 * @param hourly its on-demand price per hour, rounded to six decimals
 * @param engines the engines it is sold for; none for a compute type
 * @param burst whether it runs on CPU credits, and what AWS documents of them
 */
record Sku(String sku, String family, Shape shape, double hourly, List<String> engines, Burst burst) {

	private static final int PRICE_DECIMALS = 6;

	Sku {
		engines = List.copyOf(engines);
	}

	/**
	 * A type's price, its memory and price rounded as {@code scripts/prices} rounded
	 * them.
	 * @param sku the type
	 * @param family its family
	 * @param shape its vCPUs and memory
	 * @param hourly its on-demand price per hour
	 * @return the type, for no engine and not burstable
	 */
	static Sku of(String sku, String family, Shape shape, double hourly) {
		return new Sku(sku, family, shape.rounded(), PythonRound.round(hourly, PRICE_DECIMALS), List.of(), Burst.NONE);
	}

	/**
	 * This type, sold for these engines.
	 * @param engines the engines, such as {@code postgresql}
	 * @return the type
	 */
	Sku withEngines(Collection<String> engines) {
		return new Sku(this.sku, this.family, this.shape, this.hourly, List.copyOf(engines), this.burst);
	}

	/**
	 * This type, burstable or not.
	 * @param burstable whether it runs on CPU credits
	 * @param baselineCpu the CPU per vCPU it may use for ever, as AWS documents it; none
	 * for a family AWS's tables do not cover
	 * @return the type
	 */
	Sku withBurst(boolean burstable, @Nullable Double baselineCpu) {
		return with(new Burst(burstable, baselineCpu, this.burst.creditVcpuHour()));
	}

	/**
	 * This type, with the price of the CPU credits it spends above its baseline.
	 * @param creditVcpuHour the price of surplus CPU credits per vCPU-hour
	 * @return the type
	 */
	Sku withCredit(double creditVcpuHour) {
		return with(new Burst(this.burst.burstable(), this.burst.baselineCpu(), creditVcpuHour));
	}

	/**
	 * This type as a snapshot lists it, its keys in the order {@code scripts/prices} gave
	 * them, and the engines, the burst and its prices only when there are any.
	 * @return the type's JSON
	 */
	ObjectNode toJson() {
		ObjectNode json = JsonNodeFactory.instance.objectNode();
		json.put("sku", this.sku);
		json.put("family", this.family);
		json.set("vcpu", number(this.shape.vcpu()));
		json.set("memoryGib", number(this.shape.memoryGib()));
		json.putObject("hourly").put("on_demand", this.hourly);
		if (!this.engines.isEmpty()) {
			ArrayNode names = json.putArray("engines");
			this.engines.forEach(names::add);
		}
		this.burst.addTo(json);
		return json;
	}

	private Sku with(Burst credits) {
		return new Sku(this.sku, this.family, this.shape, this.hourly, this.engines, credits);
	}

	private static JsonNode number(Number number) {
		return (number instanceof Integer whole) ? IntNode.valueOf(whole) : DoubleNode.valueOf(number.doubleValue());
	}

	/**
	 * Whether a type runs on CPU credits rather than full cores (AWS's t families), and
	 * what AWS documents of the credits.
	 *
	 * @param burstable whether it runs on CPU credits
	 * @param baselineCpu the CPU per vCPU it may use for ever; none when unknown
	 * @param creditVcpuHour the price of surplus CPU credits per vCPU-hour, where it runs
	 * in unlimited mode; none when unknown
	 */
	record Burst(boolean burstable, @Nullable Double baselineCpu, @Nullable Double creditVcpuHour) {

		/** Not burstable. */
		static final Burst NONE = new Burst(false, null, null);

		void addTo(ObjectNode json) {
			if (this.burstable) {
				json.put("burstable", true);
			}
			if (this.baselineCpu != null) {
				json.put("baselineCpu", this.baselineCpu);
			}
			if (this.creditVcpuHour != null) {
				json.put("creditVcpuHour", this.creditVcpuHour);
			}
		}

	}

}
