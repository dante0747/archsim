---
status: proposed
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0088. Freeze the Java engine by its sources, and fix the criteria before a campaign

## Context and Problem Statement

A held-out experiment is calibrated and measured only by an engine frozen in
`benchmarks/engines.json`, so that its prediction is saved before anything about it could be
tuned ([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md)). The seal knew a
frozen engine by the Python engine's source tree.
[ADR-0062](0062-make-the-java-engine-the-harnesss-default.md) left open what identifies a frozen
Java engine, and stage 5 of the port deleted the Python one, so since then no held-out
experiment can run at all: `engines.json` lists the Java engine with `frozenAt: null`.

A strict review of the accuracy evidence (18 Nov 2026) added three things:

- **The held-out set is spent.** The review read the five held-out experiments' results, and the
  model changes it recommends (how a hyperthread turns into capacity, a cost per request that
  moves with load) are made with those results in view.
- **The rules moved while the results came in.** The disturbed-run rule (31 Oct), the p95
  criterion's wording (2 Nov), the 80% range (1 Nov) and six protocol versions were each
  documented, and each was decided after runs it applies to.
- **A median over a few runs hides a run.** pg-mixed's six runs have a median knee error of +16%
  and one run at +88%.

What identifies a frozen Java engine, when does an experiment stop being held out for an
engine, and what is fixed before a campaign measures anything?

## Decision Drivers

- A prediction of a held-out experiment is made by sources nobody could have tuned on it, and
  anyone can check that from the repository.
- The freeze is cheap enough to do before every held-out campaign.
- A reader can tell which results an engine saw.
- Changing a rule after the results are in is visible, not silent.

## Considered Options

- (a) The hash over the Java engine's three source trees, which a run's provenance already
  records (`engineSources`), written into `engines.json` at the freeze with its date and tag
- (b) The engine's build (`javaBuild`, a hash over the jars that ran)
- (c) The commit

## Decision Outcome

Chosen option: (a).

- **What a freeze is.** A tag `engine-<version>` on a commit, pushed, and in that version's
  entry of `engines.json`: `engineSources`, the SHA-256 over the trees of `archsim-engine`,
  `archsim-model` and `archsim-cli` at that commit (`Provenance.engineSources`), `frozenAt`,
  `tag`, and `harnessHash`, the harness's trees then, so that a reader can see whether the
  harness has moved since. `engine-id` prints the entry for the engine as it is and names no
  sources that have uncommitted changes (`EngineFreeze`); it writes nothing, since a freeze is
  a person's act.
- **A freeze is never edited.** Any commit in the three trees, a test or a comment included,
  makes the sources another engine. A campaign then runs from the tag's sources, or the engine
  gets a new version that is frozen in its turn; `engine-id` says so when the version is
  already frozen with other sources, where it would otherwise print an entry to overwrite.
- **The seal.** A held-out experiment may run when a frozen entry names the `engineSources` of
  what would run, that entry's tag is in the clone and holds those very sources, nothing in the
  engine or the harness is uncommitted, and `BENCH_HOST` is not on (`Seals`). An entry written
  into `engines.json` without its tag therefore opens nothing, and neither does a tag that
  holds other sources than its entry says. `BENCH_HOST=on` puts the designs on a host machine
  fitted from this machine's own checks (with `BENCH_HOST_FIT` and `BENCH_HOST_CLOCK`, on any
  fit and clock the environment names), which no freeze names, so a held-out experiment
  refuses it. The frozen Python engine was known by its tree; nothing runs it any more, and the
  seal no longer looks for one.
- **When an experiment stops being held out.** An engine lists in `tunedOn` every experiment
  whose results were in view when its model was changed; the report then labels that pair
  `tuned-on` (`Engines.sampleLabel`, unchanged). The model is the engine and what the harness
  makes of a calibration: a change to how the harness derives the model inputs or builds the
  designs (the cost per request's curve above the staircase, for one) counts as a model change
  too, and is written into the version's entry and note like an engine's. The Java engine
  predicts the 65 kept calibrations, the 15 held-out runs among them, exactly as 0.16.0 did,
  knees and steps (`./gradlew :archsim-benchmarks:parity`, `KeptRunsChecksTests`, against
  `results/dev/0.16.0-java`: 65 of 65 on 19 Nov 2026 with engine 1.5.0), so the five
  experiments of 3-4 Nov are still held out for it. The first version that changes how capacity
  or latency is modelled after 18 Nov 2026 lists all five. Its out-of-sample accuracy can then
  be shown only on experiments registered (`heldOut`: date and commit) before that change was
  made, which means registering new ones first.
- **Fixed before a campaign.** Before the first calibration of a held-out campaign: the engine
  is frozen as above; the harness is committed (the seal refuses otherwise); the experiments
  are registered; and the protocol and the criteria are the ones written in
  `docs/validation.md` at that commit (the tier thresholds, the disturbed-run rule, the knee
  search of [ADR-0086](0086-keep-the-load-open-loop-past-the-objective-and-close-in-on-the-measured-knee.md)).
  A rule changed after that campaign's first measurement is reported both ways for that
  campaign, with the date of the change, as the page already does for the p95 criterion.
- **Every run counts in a figure.** The repeatability table gains each experiment's mean
  absolute knee error over its counted runs, and the mean over every such run of the held-out
  and of the tuned-on experiments (`Repeatability.meanAbs`): 40% over the 15 held-out runs and
  16% over the 22 tuned-on ones today. `accuracy.json` carries it (`meanAbsError`). The tier
  criteria still judge the median.

### Consequences

- Good, because the Java engine can be frozen, so a held-out campaign is possible again.
- Good, because the freeze names sources, not a build: two machines that check out the tag
  agree, and a rebuilt jar does not unfreeze the engine.
- Good, because the page's own table now shows what the review computed by hand: about 40% out
  of sample against 16% in sample.
- Bad, because the five held-out experiments cannot prove the next model change. New held-out
  experiments have to be designed and registered before it, which is work the review's other
  recommendations wait for.
- Bad, because the freeze pins the engine's sources and not the harness. A harness committed
  after the freeze still runs a held-out experiment, so that a campaign is not held up by a
  fix to the runner; the entry's `harnessHash` shows that it moved, and whether the move
  changed the model is for `tunedOn` and the note to say.
- Bad, because "in view" is a matter of record and honesty, not of mechanism: nothing stops a
  version from omitting an experiment from `tunedOn`. The entry's note and this record say what
  each version saw.
- Bad, because the committed-data check of an entry against its tag is skipped where the clone
  has no tags, CI's checkout among them: there the seal's own check, made before every
  held-out run, is the one that counts.
- Neutral, because no engine is frozen by this decision. The engine that predicts the next
  held-out campaign is frozen then, with the changes that campaign is meant to test.

### Confirmation

- `SealsTests` covers a held-out experiment on the frozen engine; on sources no freeze names;
  on a freeze whose tag is not in the clone or holds other sources; with uncommitted changes;
  on a host machine; and, on a small repository, a freeze whose tag holds the entry's sources.
- `EngineFreezeTests` covers what a freeze records, an engine with uncommitted changes, and a
  version already frozen with these sources or with others; `EngineIdCommandTests` that
  `engine-id` prints it, exits with 0 and writes no file.
- `ProvenanceTests` covers the hash at a commit against the one a run from that commit
  records, a revision git cannot name, and each frozen Java entry of `engines.json` against its
  tag (skipped while none is frozen, and for a tag the clone lacks); `EnginesTests` an entry
  frozen by its sources.
- `RepeatabilityTests` and `SummaryBlocksTests` cover the mean absolute error of an
  experiment's runs and of each sample; `ReportParityTests` regenerates the page and
  `accuracy.json`.

## Pros and Cons of the Options

### (b) The engine's build

- Good, because it names exactly the bytes that ran.
- Bad, because a jar's hash changes with the build (timestamps, the JDK), so the same sources
  built twice would be two engines, and the harness runs the engine from folders of classes in
  tests, where there is no jar to hash.

### (c) The commit

- Good, because it is the simplest thing to record.
- Bad, because any later commit, a documentation fix included, would unfreeze the engine, so a
  campaign could only ever run from the freeze's own commit, with whatever harness fix it then
  lacks.
