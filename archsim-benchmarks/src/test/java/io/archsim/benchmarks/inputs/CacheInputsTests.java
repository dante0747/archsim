package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.numeric.PythonRound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Redis's command time is its CPU per command at the top calibration pass, net of idle
 * (W24): INFO commandstats' own time leaves out reading requests and writing replies,
 * about nine tenths of Redis's CPU. The service-to-Redis link is what is left of the GET
 * round trip.
 */
class CacheInputsTests {

	private static final Experiment CACHE_ASIDE = Experiments.standard().get("cache-aside");

	private static final Experiment CACHE_TTL = Experiments.standard().get("cache-ttl");

	private static final int MEAN_DECIMALS = 5;

	// 0.032 to 0.038 ms against INFO commandstats' 0.003 ms
	@ParameterizedTest(name = "replicate {0}")
	@ValueSource(ints = { 0, 1, 2 })
	void of_aReplicateOfCacheAsidesLatestRun_isRedissCpuPerCommandNetOfIdle(int index) {
		JsonNode calibration = calibration("cache-aside");
		ObjectNode replicate = replicates(calibration).get(index);
		JsonNode high = replicate.get("high");
		double calls = high.at("/redis/get/calls").doubleValue() + high.at("/redis/set/calls").doubleValue();
		double net = high.at("/containerCpuMs/redis").doubleValue()
				- calibration.at("/idle/containerCpuMsPerSecond/redis").doubleValue() * Protocol.CALIBRATION_S;

		double mean = CacheInputs.of(CACHE_ASIDE, replicate).at("/cacheServiceTimeMs/value/mean").doubleValue();

		assertThat(mean).isEqualTo(PythonRound.round(net / calls, MEAN_DECIMALS)).isBetween(0.03, 0.04);
	}

	// the command's median is its mean x ln 2
	@Test
	void of_cacheAsidesLatestReplicates_linkIsTheRestOfTheGetRoundTrip() {
		ObjectNode replicate = replicates(calibration("cache-aside")).getFirst();

		ObjectNode inputs = CacheInputs.of(CACHE_ASIDE, replicate);

		double mean = inputs.at("/cacheServiceTimeMs/value/mean").doubleValue();
		double get = replicate.at("/high/service/timings/redis_get/median").doubleValue();
		assertThat(inputs.at("/cacheLinkLatencyMs/value").doubleValue())
			.isEqualTo(PythonRound.round((get - mean * StrictMath.log(2)) / 2, 4));
		assertThat(inputs.at("/cacheServiceTimeMs/how").stringValue()).contains("net of idle");
	}

	// run 1, two passes and no idle pass: (763 - 586 ms) / (9,362 - 2,358 GET/SET calls)
	@Test
	void of_aCalibrationBeforeProtocolV5_isTheSlopeBetweenThePasses() {
		JsonNode calibration = calibration("cache-aside/run-1");

		ObjectNode inputs = CacheInputs.of(CACHE_ASIDE, calibration.get("passes"));

		assertThat(inputs.at("/cacheServiceTimeMs/value/mean").doubleValue()).isCloseTo(0.02527, within(1e-5));
		assertThat(inputs.at("/cacheServiceTimeMs/how").stringValue()).contains("per extra command between the passes");
	}

	// (2,000 - 5 x 30 ms at rest) / 45,000 GET/SET calls; cache-miss is on that stack too
	@ParameterizedTest
	@ValueSource(strings = { "cache-ttl", "cache-miss" })
	void of_anExperimentOnTheCacheTtlStack_readsThatStacksRedisContainer(String experiment) {
		ObjectNode inputs = CacheInputs.of(Experiments.standard().get(experiment), redisPasses("redis-ttl", true));

		assertThat(inputs.at("/cacheServiceTimeMs/value/mean").doubleValue())
			.isEqualTo(PythonRound.round(1850.0 / 45_000, MEAN_DECIMALS));
	}

	// INFO commandstats alone: (30,000 x 3 + 30,000) us / 45,000 calls
	@Test
	void of_redisWithoutItsCpu_keepsTheCommandTime() {
		ObjectNode inputs = CacheInputs.of(CACHE_TTL, redisPasses("redis-ttl", false));

		assertThat(inputs.at("/cacheServiceTimeMs/value/mean").doubleValue())
			.isEqualTo(PythonRound.round(120_000.0 / 45_000 / 1000, MEAN_DECIMALS));
		assertThat(inputs.at("/cacheServiceTimeMs/how").stringValue()).contains("no CPU figure");
	}

	private static JsonNode calibration(String folder) {
		return ResultFiles.read(RepositoryLayout.current().results().resolve(folder).resolve("calibration.json"))
			.orElseThrow();
	}

	// the replicate sets the inputs were derived
	// from: each top pass with the low and idle
	private static List<ObjectNode> replicates(JsonNode calibration) {
		return calibration.get("replicates").valueStream().map((replicate) -> {
			ObjectNode set = ((ObjectNode) replicate).deepCopy();
			set.set("low", calibration.get("passes").get("low"));
			set.set("idle", calibration.get("idle"));
			return set;
		}).toList();
	}

	// a cache-ttl replicate set: Redis's statistics in the service's /stats
	// (REDIS_STATS=1), with or without the CPU of its container and an idle pass
	private static ObjectNode redisPasses(String container, boolean withCpu) {
		ObjectNode passes = JsonText.object("{}");
		passes.set("low", entry(50, 1500, withCpu ? "{\"" + container + "\": 300.0}" : "{}"));
		passes.set("high", entry(1000, 30_000, withCpu ? "{\"" + container + "\": 2000.0}" : "{}"));
		if (withCpu) {
			passes.set("idle", JsonText.read("{\"containerCpuMsPerSecond\": {\"" + container + "\": 5.0}}"));
		}
		return passes;
	}

	private static JsonNode entry(int rate, int gets, String cpu) {
		ObjectNode entry = JsonText.object("{\"rateRps\": " + rate + ", \"k6\": {\"p50\": 1.0, \"throughputRps\": "
				+ rate + "}, \"service\": {\"timings\": {\"redis_get\": {\"count\": " + gets
				+ ", \"mean\": 0.3, \"median\": 0.25, \"p95\": 0.6, \"p99\": 0.9}}}}");
		ObjectNode redis = ((ObjectNode) entry.get("service")).putObject("redis");
		redis.putObject("get").put("calls", gets).put("usec", gets * 3.0);
		redis.putObject("set").put("calls", gets / 2).put("usec", gets);
		entry.set("containerCpuMs", JsonText.read(cpu));
		return entry;
	}

}
