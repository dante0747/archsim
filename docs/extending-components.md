# Adding a component type

A component type is part of ArchSim's source: its settings and ports in `archsim-model`, its
behaviour during a run in `archsim-engine`. The API serves every type through
`GET /api/v1/components`, and the web editor builds its palette entry and inspector form from that
catalog, so a new type needs **no API or web app change**. The engine loads no plugins; a type is
added by a change to the repository.

Read [`simulation-model.md`](simulation-model.md) first: your component must keep request
accounting exact. This guide follows the simplest type, the external API (`external_api`): a
third-party service each call waits on, which may fail or refuse the call.

## Where a type lives

| What | Where | The external API's |
| --- | --- | --- |
| Settings: a record, its defaults, bounds and form hints | `archsim-model`, `io.archsim.model.component.<type>` | `ExternalApiConfig` |
| The settings' descriptions and form groups | the package's `Descriptions` class | `external/Descriptions` |
| The type: name, category, settings record, ports, checks | a `ComponentSpec` subclass, same package | `ExternalApiSpec` |
| Being one of ArchSim's types | `StandardComponents` (`component.standard`) | `new ExternalApiSpec()` |
| Description, icon, what is and isn't modelled, presets | `archsim-model/src/main/resources/io/archsim/model/catalog/<type>.json` | `external_api.json` |
| Behaviour during a run | a `RuntimeComponent` subclass, `archsim-engine`'s `io.archsim.engine.components.<type>` | `ExternalApiRuntime` |
| Building it for a run | one `case` in `Parts.build` (`io.archsim.engine.simulator`) | `case ExternalApiConfig api -> ...` |

Other parts of the engine name some types where they treat them specially, and fall back to the
type's category for any other: the cost estimate (`CostEstimator`), the load families
(`ComponentFamily`), the findings, and the importers. The web app's short names and colours
(`apps/web/src/lib/theme.ts`) do the same. Add your type there only if the category's default is
wrong for it.

## 1. The settings

A record whose components are the settings, read by the model's codec (`io.archsim.model.codec`,
[ADR-0022](adr/0022-records-and-a-pydantic-compatible-codec.md)): the JSON name is the
component's, unknown keys are refused, and the annotations give the default, the bounds, the
description and the inspector's hints.

```java
public record ExternalApiConfig(
		@Default("generic") @Description(Descriptions.TECHNOLOGY) @Ui(label = "Kind of service",
				group = Descriptions.TECHNOLOGY_GROUP) ExternalApiTechnology technology,

		@DefaultFrom("DEFAULT_LATENCY") @Description(Descriptions.LATENCY_MS) @Ui(label = "Response time", unit = "ms",
				group = Descriptions.PERFORMANCE_GROUP, assumption = true, headline = true) Distribution latencyMs,

		@Default("0.001") @Description(Descriptions.ERROR_RATE) @Ui(group = Descriptions.RELIABILITY_GROUP,
				assumption = true, headline = true, step = 0.001) @Ge(0) @Le(1) double errorRate,
		...) {

	/** The provider's default response time: 120 ms median, 500 ms p95. */
	static final Distribution DEFAULT_LATENCY = new LogNormal(120.0, 500.0);

}
```

- `@Default` holds the default as JSON text; `@DefaultFrom` names a constant, for a default that
  is a record. A setting with neither is required, and `@Nullable` makes "not set" a value.
- `@Ge`, `@Gt`, `@Le`, `@Lt`, `@MinLength`, `@MaxLength` and `@Matches` are checked when a
  design is read, with pydantic's messages
  ([ADR-0024](adr/0024-validation-issues-with-stable-codes.md)).
- `@Ui` drives the inspector: `assumption = true` marks a value the user asserts (a service
  time, a hit ratio) rather than a structural setting, which the inspector tags and a run's
  report lists among its inputs, assumed or measured; `headline = true` shows the setting on the
  part's card.
- A choice of words is a `JsonEnum` (`ExternalApiTechnology`), one constant per line, each with
  its Javadoc.
- The texts go in the package's `Descriptions` class, so the annotations stay short
  ([Java conventions](java-conventions.md)).

## 2. The type

```java
public final class ExternalApiSpec extends ComponentSpec<ExternalApiConfig> {

	/** The component type's name in documents. */
	public static final String TYPE = "external_api";

	public ExternalApiSpec() {
		super(new ComponentIdentity(TYPE, "External API", Category.EXTERNAL), ExternalApiConfig.class,
				List.of(Port.in("in", "Calls", "Calls to the third-party service.")));
	}

}
```

The ports say what may link to the part (`Port.in`, `Port.out`) and how many links each takes
(`withMinEdges`, `withMaxEdges`).
Override `validate` for checks of a node beyond its ports (a setting that contradicts its links,
say), returning `Issue`s with a stable code, and `emittedTypes` when the part changes the
request types that leave it (a cache sends only its misses on). Then add the type to
`StandardComponents`, in the order the palette lists it.

## 3. The catalog entry

`catalog/<type>.json` holds what the palette and the assumptions page show: the description, the
icon (a lucide name or a Simple Icons slug), `modeled` and `notModeled` (shown in the part's
assumptions panel, and in [`assumptions.md`](assumptions.md)) and the presets, each a named set of
settings with its sources. A type without one fails as soon as the catalog is built, naming the
file to add, and `ComponentCatalogTests` checks each preset against the type's settings.

## 4. The behaviour during a run

A `RuntimeComponent` handles one request at a time, in simulated time, and answers with an
`Outcome` through the callback it is given:

```java
public final class ExternalApiRuntime extends RuntimeComponent {

	public ExternalApiRuntime(SimContext context, NodeSpec node, ExternalApiConfig config) {
		super(context, node, slots(config.concurrencyLimit()));
		this.latency = Sampler.of(config.latencyMs());
		this.latencyStream = context.stream("node:" + node.id() + ":latency");
		...
	}

	@Override
	public void handle(Request request, Consumer<Outcome> done) {
		double start = now();
		metrics().arrive(start);
		...
		double took = sampleTime(this.latency, this.latencyStream);
		simulation().after(took, () -> answer(request, start, took, done));
	}

}
```

What every component keeps:

- **Accounting.** Each request that arrives (`metrics().arrive`) departs exactly once
  (`metrics().depart`) with one outcome, and `done` is called exactly once, so that
  `arrivals == completed + failedDownstream + rejected + inProgress` holds for the part at the end
  of a run (`NodeMetrics`).
- **Time.** Nothing sleeps or reads a clock: work takes simulated time through
  `simulation().after(...)` and the kernel's resources
  ([ADR-0013](adr/0013-event-driven-simulation-kernel.md)).
- **Randomness.** Each random draw comes from a stream of the part's own,
  `context.stream("node:<id>:<purpose>")`, so a run is the same for the same seed whatever else
  the design holds ([ADR-0047](adr/0047-draw-python-random-streams-in-java.md)).
- **Metrics.** `metrics().busy()` and the service and hold histograms feed utilization and the
  report's latencies; `metrics().count(name, time)` adds a counter to the part's summary.
- **Calls downstream.** A part that calls others sends requests through `outEdges(port)` and the
  run's call helpers, as `ServiceRuntime` does; the external API calls nothing.

Optional overrides add to the report: `extraSummary` and `extraTimeseries` (the part's own
figures), `explain` (the formulas the results panel shows) and `behaviour` (the technology's
traits).

Finally, add a `case` for the settings record to `Parts.build`.

## 5. Tests

- The settings and checks: a test of the spec in `archsim-model` (defaults, bounds, `validate`),
  beside the other types' (`component/<type>/`).
- The behaviour: tests of the runtime in `archsim-engine` (`components/<type>/`), running a small
  design through the `Simulator` and asserting what the report says: counts, latencies, failures.
- The whole engine's checks must still pass, unchanged: the golden files and the frozen engine's
  runs ([parity/README.md](../parity/README.md)) and the checks against queueing theory
  (`QueueingTheoryTests`, `StarterReferenceTests`, `EstimateAgreementTests`). A new type changes no
  existing design's results, so the engine's version stays as it is
  ([CONTRIBUTING](../CONTRIBUTING.md#changing-simulation-semantics)).

## 6. What to regenerate

- `./gradlew :archsim-engine:generate` rewrites the web app's catalog fixture
  (`apps/web/src/test/fixtures/catalog.json`) and [`assumptions.md`](assumptions.md), which
  `GeneratedFileTests` holds to the engine's output.
- The JSON Schema (`GET /api/v1/schema/architecture`, `archsim schema`) is generated from the
  records, so it changes by itself.
- [`simulation-model.md`](simulation-model.md) says what the new part models and what it
  doesn't.
