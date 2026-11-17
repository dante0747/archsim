package io.archsim.engine.parity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.text.QueueText;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * Where the Java departs from the frozen engine on a queue with more consumers than
 * partitions (engine 1.5.0, report 1.20, ADR-0085), and how it is read as the frozen
 * engine's all the same, guided by the frozen engine's output of the same call: the queue
 * reports its idle consumers ({@code idleConsumers}), and its lag finding says why they
 * sat idle and advises on them. They read as Python's once the idle consumers are shown
 * to be the queue's consumers beyond its effective ones, as its design sets them, and the
 * finding's sentence is the one they make: the figure, the sentence and the evidence are
 * left out, and the advice is Python's.
 */
public final class IdleConsumerDepartures {

	private static final String IDLE = "idleConsumers";

	private static final String SUMMARY = "summary";

	private IdleConsumerDepartures() {
	}

	/**
	 * The Java's report with its idle consumers read as the frozen engine's.
	 * @param java the Java's report, which is not changed
	 * @param python the frozen engine's report of the same call
	 * @return a copy of the Java's report
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		for (JsonNode node : copy.path("nodes")) {
			JsonNode summary = node.path(SUMMARY);
			if (MessageQueueSpec.TYPE.equals(node.path("type").stringValue("")) && summary.has(IDLE)
					&& isConsumersBeyondEffective(node, summary)) {
				putLagFinding(copy, python, node.path("id").stringValue(""), sentence(summary));
				((ObjectNode) summary).remove(IDLE);
			}
		}
		return copy;
	}

	// the queue's consumers, as its design sets them, beyond those that have work
	private static boolean isConsumersBeyondEffective(JsonNode node, JsonNode summary) {
		long consumers = node.path("config").path("consumers").asLong(-1);
		return summary.path(IDLE).asLong() == consumers - summary.path("effectiveConsumers").asLong(-1);
	}

	// the lag finding's sentence the queue's idle consumers make
	private static String sentence(JsonNode summary) {
		long idle = summary.path(IDLE).asLong();
		long busy = summary.path("effectiveConsumers").asLong();
		return " " + idle + " of the " + (busy + idle) + " consumers sat idle: only "
				+ QueueText.ONE_CONSUMER_PER_PARTITION + ", so " + busy + " partitions keep at most " + busy
				+ " of them busy.";
	}

	// the queue's lag finding without the sentence and evidence, with Python's advice
	private static void putLagFinding(JsonNode java, JsonNode python, String queueId, String sentence) {
		JsonNode findings = java.path("findings");
		for (int index = 0; index < findings.size(); index++) {
			ObjectNode finding = (ObjectNode) findings.get(index);
			boolean lag = "queue_lag".equals(finding.path("kind").stringValue(""));
			String detail = finding.path("detail").stringValue("");
			if (lag && queueId.equals(finding.path("nodeId").stringValue("")) && detail.contains(sentence)) {
				finding.put("detail", detail.replace(sentence, ""));
				((ObjectNode) finding.path("evidence")).remove(IDLE);
				finding.set("suggestion", python.path("findings").path(index).path("suggestion"));
			}
		}
	}

}
