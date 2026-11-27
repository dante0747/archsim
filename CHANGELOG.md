# Changelog

Notable changes to ArchSim, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every simulation report also names the
engine version that made it; see [CONTRIBUTING.md](CONTRIBUTING.md#changing-simulation-semantics)
for when that version changes.

## [Unreleased]

### Security

- **Limits per address count an IPv6 address by its /64, and sandboxes' runs have budgets of
  their own** ([ADR-0106](docs/adr/0106-count-addresses-by-network-and-give-sandboxes-runs-a-budget.md),
  proposed). An IPv4 address mapped into IPv6 counts as itself. Sandboxes' runs count against
  their address's 40 an hour and all sandboxes' 300 an hour, and workers take every other
  workspace's queued runs before a sandbox's.

## [1.5.0] - 2026-11-27

Security fixes for a server other people can reach, and screens that lead with the answer. Results
do not change: the engine stays 1.6.0 and designs stay on schema 1.11. Upgrade a shared server;
the entries under Security say what each fix changes. Four things to know first: the database gets
one migration, which rewrites the copies share links keep; the API now deletes a project's runs
past its newest `ARCHSIM_RUN_HISTORY_LIMIT` (200), so raise it before upgrading a server that
should keep more; an API token no longer changes a workspace's members, roles, invitations or
name; and without sign-in the API answers only requests addressed to this machine or to a name
`ARCHSIM_ALLOWED_HOSTS` lists.

### Security

- **A design's numbers must be finite
  ([ADR-0103](docs/adr/0103-refuse-a-number-that-is-not-finite-when-a-design-is-read.md),
  proposed).** Such a number was read as a float and could get past the checks that bound a run;
  it is now refused where it stands, with "Input should be a finite number". The schema version
  stays 1.11.
- **After signing in, the web app goes back only to a page of its own site.** The sign-in page
  resolves the page to go back to as the browser will, and keeps it only when it stays on the
  site, as the server already checks it.
- **The web server caps a request body at the API's limit, and waits at most five minutes for the
  API.** Its proxy refuses a larger body before buffering it (413) and answers 504 when the API
  does not answer in time; the hosting kit's Caddy caps bodies at the same 5 MB.
- **An API token cannot change a workspace's members, their roles or its invitations, or rename
  it.** These take the person signed in to the web app, as making a workspace or an invitation
  already did; a sandbox's guest cannot rename the sandbox either.
- **A share link hides a calibrated design's telemetry address wherever the design names it.** It
  was hidden in the design's calibration only; it is now hidden in every measured input's
  provenance and in the report's assumptions too, and links already made are rewritten by a
  migration.
- **A request holds one database connection at a time
  ([ADR-0105](docs/adr/0105-charge-every-rate-limit-before-its-transaction-begins.md), proposed).**
  Comments, sign-offs, share links, invitations and team workspaces charge their rate limit before
  their transaction begins, as sandbox starts do, and a charge made inside a transaction is
  refused. The connection pool stays at ten connections, for the API and the worker alike, now set
  explicitly.
- **Without sign-in, the API answers only this machine's own clients
  ([ADR-0107](docs/adr/0107-without-sign-in-answer-only-this-machines-own-clients.md), proposed).**
  It refuses a request addressed to a host name other than this machine's or one
  `ARCHSIM_ALLOWED_HOSTS` lists, a change from another site's page, and a body that is not JSON.
  The web app's proxy passes the browser's host and origin on, so requests made through it are
  checked the same way. Compose's API allows `api`, the name the web container calls it by.

### Changed

- **The API deletes what it no longer keeps, when it starts and every hour after
  ([ADR-0111](docs/adr/0111-prune-the-runs-past-a-projects-limit-and-expired-rows-every-hour.md),
  proposed).** A project keeps its newest `ARCHSIM_RUN_HISTORY_LIMIT` runs (200), the ones its
  list shows; older runs are deleted, except runs still queued or running, runs with a sign-off or
  a comment, and the other runs of their seed group. Expired sessions and invitations are deleted,
  and so are expired share links' copies of the results (the links stay, listed as expired). The
  history keeps each change `ARCHSIM_HISTORY_DAYS` (365) days. Before upgrading a server that set
  `ARCHSIM_RUN_HISTORY_LIMIT` low to shorten the list, raise it: the first prune deletes the runs
  past it.
- **Results lead with the answer, and every caveat explains itself on demand
  ([ADR-0102](docs/adr/0102-lead-each-screen-with-the-answer-and-make-every-input-explain-itself.md),
  proposed).** Results → Answer is one card: what the verdict rests on ("Misses 2 of 2
  objectives"), the headline, the bottleneck, and the objectives with a target as tiles, with one
  tile to add the others. Under it, "How sure is this" gives each caveat a line (the seeds, the
  confidence, experimental parts, what queueing formulas predict, a quick check's sample), its
  explanation in a popover. Then four key numbers, what to fix first by title with what to try
  and its evidence under "Why", the month's cost as one bar split by part, and a compact review.
  "Show on canvas" now opens Design with the part selected; before, it selected the part behind
  the Results page.
- **Every input says what it is and where its value comes from.** A setting's label shows in
  full, with a one-line help under it, a tag from the design's analysis (measured, documented
  with the vendor's site, your estimate, or default: an illustrative default, as the confidence
  counts it) and a reset to the part's default when its value differs. The side panel is 352 px
  wide. A part's resources fold why the auto size, what it needs and which price the cost uses
  under one line, and its facts from the run read one a line.
- **The parts library lists parts by their full names**, with what each is beneath, instead of a
  grid of tiles that cut long names short.
- **archsim.io loads one stylesheet of its own
  ([ADR-0100](docs/adr/0100-give-the-website-a-stylesheet-of-its-own-built-from-the-files-it-renders.md),
  proposed).** Every page waited for the app's stylesheet, with Tailwind's classes from every file
  of the web app and React Flow's styles, which the site never shows. The site's own sheet holds
  only the classes its pages render. It was 12 KB gzipped when it replaced the three sheets every
  page loaded (the app's, the site's and the fonts'), which were 22 KB, and on a throttled phone
  the home page's first contentful paint fell from 4.6 s to 3.6 s.

### Fixed

- **No label is cut short on the canvas or the timeline.** The chaos pads clipped their titles
  ("Lose Catalog…"); they have two columns and room for three lines. A part's card cut a label
  when its value was long ("Max connections per instan…"); the value now wraps under it. The Test
  timeline printed the traffic scale on top of the drain band's label ("2K/sdrain"); the scale
  ends where the band starts. The lanes' legend sits above the playhead.
- **The run picker in Results and on the Dashboard shows the whole label of its run.** Since a
  run's label names its short id ("Current design · run 3f9a2c1d · seed 42 · 43s ago"), the picker
  cut it off mid-word at common desktop widths ("· 12s a"), in the app and in the README's and
  archsim.io's screenshots. It is now as wide as the label of the run it shows, up to 28rem; a
  longer label ends in an ellipsis and stays whole in its tooltip, and in a crowded header the
  picker gives way while the other controls keep their width. The screenshots that show it, and
  the demo GIF, are retaken.
- **`/accuracy` takes its comparison with plain bottleneck arithmetic from the data.** Its lead
  said it in words typed by hand, which a new campaign could leave behind; it is now built from
  `accuracy.json`, and the short version opens with the README's statement and its figures: on a
  typical held-out run ArchSim is level with bottleneck arithmetic on the same calibration (median
  error 16.5% against 16.2% over the 15 runs), and its advantage is the worst case (-27% against
  +79%). A table adds the engine's analytic estimate and the utilisation line, each with its
  median and its worst run. `accuracy.json` carries the figures
  (`summary.againstEstimatesHeldOut`), and "level" is a sign test over the experiments, one vote
  each, as an experiment's runs share their error: by each one's median error ArchSim is the
  nearer in 3 of the 5 and the arithmetic in 2
  ([ADR-0101](docs/adr/0101-show-the-held-out-runs-against-the-simple-estimates-on-the-accuracy-page.md),
  proposed).

## [1.4.0] - 2026-11-25

Hyperthreads counted by default, the second held-out accuracy campaign, and results presented as
hypotheses to test. Results change: the engine moves from 1.5.0 to 1.6.0. Its instant estimate
changes for the same design and seed, and so does a run of a design whose vCPUs count as
hyperthreads. Designs move to schema 1.11: older ones are upgraded when read and keep their
results, unless an auto-sized part sets its own sibling speed, which is now sized for its
hyperthreads. A new design, one started from a template included, counts a vCPU on most x86
instances as a hyperthread, so it can show less capacity and a higher monthly cost (both under
Changed).

### Added

- **The website is also at [archsim.coderator.dev](https://archsim.coderator.dev)
  ([ADR-0098](docs/adr/0098-serve-the-website-at-a-second-address-from-cloudflare-pages.md)),** for
  networks that block newly registered domains such as archsim.io. Cloudflare Pages serves the
  files GitHub Pages serves, deployed by the same run; every page still names archsim.io as its
  address for search engines, and a link in the docs to archsim.io itself keeps a reader at the
  address they opened.

### Changed

- **The README, archsim.io, the app's home page and the docs present ArchSim as an architecture
  experimentation tool
  ([ADR-0099](docs/adr/0099-present-results-as-hypotheses-to-test-not-verdicts-or-gates.md),
  proposed).** A result is a hypothesis to test under the assumptions stated, not a straight yes or
  no, sign-off, audit evidence or a deployment gate. On archsim.io the story ends in "Learn what to
  test" and "Challenge your assumptions before you build.", and the CI way in is a check, "a warning
  to read, not a gate"; the accuracy section, the README's statement, now says the figures are for
  calibrated models, and `/accuracy` says how its predictions compare with bottleneck arithmetic.
  `docs/ci.md`, now "Checking designs in CI", recommends running `archsim check` as a warning
  (`continue-on-error: true`), not as a required check. The copy alone changes: the check, the
  reviews of runs and the results' words work as before.
- **archsim.io's home page is redesigned
  ([ADR-0097](docs/adr/0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md)).**
  One story, about 60% shorter: what ArchSim does and one call to action, "Try ArchSim", then the
  problem, three steps, six capabilities, three real product screens in tabs, eight core
  technologies, the accuracy figures with their caveat, and the quick start. The screens are sharp
  renditions of the README's screenshots, cropped to stay readable on a phone, in place of the
  scripted replay. Buttons, type and spacing follow one system; the calls to action stack at full
  width on a phone; the header has three sections and a phone menu that keeps focus and closes with
  Escape. The accuracy section is the README's statement word for word, the tie with plain
  bottleneck arithmetic and the latency criteria included. The license, contributing and security
  links moved to a four-group footer. axe-core finds no WCAG 2.2 AA violations on the page, and the
  site's link check now reads `srcset`. The header's, footer's and home page's links no longer
  prefetch other pages' data as they scroll into view.
- **archsim.io no longer flashes light before turning dark.** Its HTML starts dark and the theme
  script runs as the page is read, not once Next.js's scripts load; the browser bar gets one
  theme-color tag in either theme.
- **The app no longer flashes light before turning dark either.** Its HTML starts dark as well,
  and the theme script runs as the page is read, not once Next.js's scripts load: on a slow
  connection the projects home, the editor and the account pages no longer show the light theme
  first and then animate to dark.
- **The second held-out campaign is published
  ([ADR-0090](docs/adr/0090-register-a-second-held-out-set-before-the-next-model-change.md)).**
  The frozen engine 1.6.0 predicted all fourteen experiments before they were measured, three
  rounds each on 21-23 Nov 2026. The five held-out ones have median knee errors from
  -24.5% to +15.1%, all within ±25%, and the predicted range held 12 of their 15 runs
  (python-wide 1 of 3). Over those runs ArchSim's median error (16.5%) ties bottleneck arithmetic's
  (16.2%); its worst run is -27% against +79%, and none over-predicted capacity by more than 25%
  against the arithmetic's 7. Only go-pool also meets the latency and stability criteria, and
  nothing is validated without a second machine. `docs/validation.md`, `/accuracy` and the README
  lead with these results, misses included, and the report lists the newest held-out set first.
- **Engine 1.6.0 is frozen to predict the second held-out set
  ([ADR-0088](docs/adr/0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md)).**
  Its entry in `benchmarks/engines.json` names the hash over its source trees, the harness's
  trees then, the tag `engine-1.6.0` and the host machine its designs run on (`hostFit`,
  ADR-0095). The held-out experiments, the five ADR-0090 registered among them, may now be
  calibrated and measured, by this engine alone: from its tag's sources, with nothing
  uncommitted, on the host machine its freeze names. Every other engine, and this one on other
  sources or another fit, still only smoke-tests them.
- **The accuracy benchmarks' designs run on the laptop's fitted host machine, and a freeze names
  it ([ADR-0095](docs/adr/0095-run-the-benchmark-designs-on-the-laptops-fitted-host-machine-and-let-a-freeze-name-it.md)).**
  Every benchmark stack shares the laptop's 4 cores of 8 threads with k6, and with the law of
  ADR-0093 alone the knees of 7 of the 9 experiments were predicted too high, java-mixed's by 72%.
  The harness now puts the designs on the host machine the laptop's contention checks fit (the
  probe of the latest check with one, the machine at rest and k6 of the latest quiet one, all
  measured on 20 Nov 2026) unless `BENCH_HOST=off`, where it was opt-in (`BENCH_HOST=on`).
  Re-simulated in-sample on the 37 counted v6 runs, the runs whose knee is predicted too high
  fall from 25 to 4 and the largest over-prediction of an experiment's median from +72% to +16%
  (python-mixed), while the median absolute error rises from 12.9% to 14.5% and the largest
  under-prediction is cache-aside's -29%. Only the checks that name the machine count, so on
  another machine, a CI runner among them, the designs have no host machine until it is checked;
  `BENCH_HOST` is `on` or `off`, and another value is refused. `engine-id` records the fit's
  digest, of its numbers, in a freeze's entry (`hostFit`), and names none from a what-if or from
  checks not committed; a held-out experiment runs only on the fit its freeze names, where a
  host machine kept it sealed. The predicted range, refitted on these knees, holds 37 of the 49
  runs leaving out each experiment, about x0.81 to x1.23 of the predicted knee, 10 of its 12
  misses above it. The engine's results do not change.
- **The accuracy benchmarks' predicted range is fitted on all nine measured experiments, with an
  error each experiment's runs share ([ADR-0094](docs/adr/0094-fit-the-knees-range-on-every-experiment-the-engine-is-tuned-on-with-an-error-each-experiment-shares.md)).**
  The 80% range around a predicted knee took the model's own error from the four tuned-on
  experiments alone, as one error per run (0.15), and on the held-out campaign it held 9 of 15
  runs, every miss below it. `range-fit` now fits on every experiment the engine is tuned on
  (`engines.json`), all nine for engine 1.6.0, and splits the model's error into one each
  experiment's runs share and one each run has, by maximum likelihood: 0.32 and 0. Leaving out
  each run's experiment, the range holds 41 of 49 runs (84%), where the earlier error held 32;
  it is wider, about x0.64 to x1.56 of the predicted knee where it was about x0.75 to x1.33 (at
  each fit's runs' median calibration spread), and its eight misses are all below it. The fit's
  `check.before` now counts the ranges the runs were predicted with by nominal coverage.
- **The accuracy benchmarks fit a service's falling CPU per request as a law of its rate
  ([ADR-0093](docs/adr/0093-fit-a-services-falling-cpu-per-request-as-a-law-of-its-rate.md)).** The benchmark harness charged a service the CPU per
  request of its calibration staircase's top rate at every rate above it, while the measured cost
  kept falling: near the knee it charged 16% too much (the median over the kept v6 runs' steps at
  75% busy or more). It now fits the staircase's costs as `floor + shared x rate^-k` (the harness's
  new `CostLaws`, with `k` at most 1) and carries the law above the top to where the service's
  CPUs fill, but no further than twice the top rate, as the `serviceTimeByLoad` table the engine
  already reads: 5.4% there, erring high. Re-simulated in-sample, cache-aside's median knee error
  moves from -22% to -7% and the median of the 37 counted v6 runs' absolute errors from 16.3% to
  12.9%; cache-ttl, chain, Kafka and pg-mixed move 3 to 16 points toward promising more capacity,
  which the laptop's own slowdown, not modelled while `BENCH_HOST` is off, held back. With W27's
  host machine as well (a what-if: two of its numbers are assumed) every experiment is within
  20% and the median is 11.9%. On a host machine the law is fitted on the work per request, so the
  machine's slowdown is not counted twice. The engine's results do not change, so it stays 1.6.0;
  `archsim calibrate` keeps fitting customers' telemetry as before.
- **A vCPU counts as a hyperthread unless the design or its instance family says otherwise (schema
  1.11, engine 1.6.0, ADR-0091).** A service, database or cache with 2 or more vCPU, on a design
  without a `host`, runs on hyperthread pairs at the first sibling speed that applies: its own
  `siblingSpeed` (1 means whole cores); the design's new `architecture.siblingSpeed`; 1 when the
  instance type the cost prices for it is of a family whose vCPUs are whole cores (Graviton and
  other Arm families, AWS T2 and its AMD families from M7a, listed with their sources on
  [assumptions.md](docs/assumptions.md)), when the service runs serverless, or when it runs as
  pods on Kubernetes; else 0.6, the middle of what published measurements of request-serving work
  give, from vendors' documentation and others' benchmarks rather than a measurement of ArchSim's.
  Before, every such vCPU was a whole core, so a part bound by its CPU on most x86 instances was
  predicted up to 1.67 times the capacity it has. One resolver applies the rule when a run's
  document is resolved and writes into each part that sets no speed the one it counts at
  (inferred, or the design's below 1), so the simulation, the instant estimate, the review, the
  cost, sweeps and checks read the same speed, and the run's `input` says what ran. Auto-sizing
  sizes a part on the default for both kinds from the same work and gives it the cheapest type
  that covers its own family's need, a type set on the part while it covers its need (one too
  small gives way to the type the part gets, which the run's input then records, so the cost
  prices that one and the run counts its vCPUs); at generic prices, where a shape has no family,
  it sizes it on pairs. A part that keeps one vCPU but lands on a shape or type of two or more,
  for its memory or because none smaller is sold, now needs that machine to cover its need on
  pairs (at a sibling speed of 0.3 it got 2 vCPU and refused requests; it gets 4). Each sized
  part's summary says how its vCPUs counted (`vcpus`: the speed, why and the words, outside the
  result digest), the report's model notes name the parts on the default and the pods (counted as
  whole cores, which is optimistic on an x86 node pool), and the editor's design overview shows
  the design's sibling speed and clears it in one click. **A saved design keeps its results,**
  unless an auto-sized part in it sets its own `siblingSpeed` (the next entry): the upgrade to 1.11
  writes `architecture.siblingSpeed: 1` once and leaves its parts as they are, so its runs and
  their result digests are what they were; clearing the setting puts it on the default. The
  accuracy benchmarks' designs are written at schemas 1.0 and 1.7, so no simulated knee moves. An
  API of 2 replicas (10 ms of CPU a request) and a PostgreSQL at 480 req/s,
  auto-sized at schema 1.11: at generic prices each gets 8 vCPU where it got 4, the month costs
  $747 where it cost $374, and the API's CPU ceiling is 960 req/s where it was 800, its vCPUs 31%
  busy at the peak where they were 60%; with the price list limited to x86 families (c6i, m6i,
  r6i) they get c6i.2xlarge and db.m6i.2xlarge where they got the xlarge, $1,017 a month where it
  was $509; on AWS unrestricted they keep c6g.xlarge and db.m6g.xlarge, whose vCPUs are whole
  cores, at $432. Sized by hand at 4 vCPU on c6i and db.m6i, the same design's API fills at 480
  req/s where it filled at 800, and its p95 at 480 req/s is 244 ms where it was 39 ms.
- **A part that sets its own sibling speed is sized for its hyperthreads (engine 1.6.0).**
  Auto-sizing slowed a part's work only on a design with a `host`, so a service, database or cache
  that set `siblingSpeed` was sized as if its vCPUs were whole cores, and its run then had every
  vCPU busy or past it. On a design without a host, such a part now needs its whole-core work over
  its sibling speed, at the same 60% target: a service doing 2.4 cores' work at its peak at 0.6
  gets 8 vCPU where it got 4, and runs its peak with 33% of them busy where 99% were. A part that
  gets one vCPU on whole cores keeps it, and so does a part with one handler. The upgrade to schema
  1.11 leaves a part's own speed as it is, so this changes an auto-sized part's size, cost, runs and
  result digests in a saved design too.
- **The templates start new designs on the default (schema 1.11).** The 25 templates are written at
  schema 1.11 without the upgrade's `architecture.siblingSpeed: 1`, since a design started from one
  is new. 24 keep their figures, 12 of them gaining the model note on pods. The .NET SaaS on SQL
  Server template's database, whose month-end story was tuned on 8 whole cores, would count its 8
  vCPU as 4 cores and succeed 74.9% at month-end where it succeeded 93.5%; it has 16 vCPU now, the
  same 8 cores with two threads each (c6i.4xlarge, $1,325 a month where the template cost $1,077).
  Its month-end close succeeds 91.3% (p95 1.4 s where it was 1.1 s), and with RCSI 99.4% (p95 608 ms
  where it was 470 ms), its headline now the API, whose threads fill behind the sessions when
  waiting postings hold them.

### Fixed

- **A Kubernetes service's pods follow the autoscaler in a replay.** In a design without a
  Kubernetes cluster part, such as the Black Friday template, a service on the Kubernetes platform
  kept the pods it starts with all run long: the Checkout API showed 3 pods while the autoscaler
  took it to 10. A run records pods only for a service on a cluster part, and the card did not
  fall back to the replicas the run recorded for every autoscaled service. Now it does, so the
  pods grow and shrink with the autoscaler. An autoscaled service's card also shows its range,
  "Autoscaling 3–14 replicas", where it showed the replicas it starts with as if they were fixed.
- **The import report and guide say what the autoscaler reads.** They said ArchSim scales on CPU
  only. It scales on the share of busy workers or, when the replica has a CPU size, the share of
  its CPU in use, whichever is higher. Memory and custom metrics, and a CPU target given as an
  average value, are still ignored. Only words change, so the engine stays at 1.6.0; a held-out
  run still uses the sources of its tag, `engine-1.6.0` (ADR-0088).
- **The instant estimate saturates a part on its own hyperthreads where they fill (engine 1.6.0,
  ADR-0091).** A part with 2 or more vCPU and a sibling speed below 1 (its own, the design's or,
  from schema 1.11, the default) fills its vCPUs where every one is busy at the sibling speed, but
  the estimate extrapolated its saturation point from the speed its vCPUs run at the peak, faster
  while some siblings idle, and past its vCPUs it counted them taking turns at its own work on top
  of the queue its CPU share past 1 already counts. A service of 4 vCPU at 0.6 doing 10 ms of CPU
  a request, whose vCPUs fill at 240 req/s, read a saturation point of 400 req/s at 200 req/s, and
  at 300 req/s needed 8094% of its capacity, its work 1,079 ms a request, and saturated at about 4
  req/s. It now saturates at 240 req/s at either load, is busy (83% of its ceiling) at 200 req/s
  and needs 125% at 300 req/s, its work 16.7 ms a request. Its utilization stays the share of its
  vCPUs busy at the peak, as a run measures it. The design review's CPU check of such a part, as
  the estimate's load does, judges it by the share of its ceiling its work takes and says so (the
  peak would take 125% of the service's ceiling at 300 req/s, where it said its CPU would be 8094%
  busy); it judged it at the speed at the peak, so it said nothing from about 80% to 93% of the
  ceiling at 0.6, and now finds little CPU headroom at 200 and 220 req/s (83% and 92% of the
  ceiling). A design on a host machine is left as it was: there the turns are other parts'
  threads, which a part's own share does not count. The frozen engine counted the turns too, so
  its recorded estimates of such parts depart: one of the estimate's cases and two recorded runs,
  whose figures, one run's CPU finding and the finding of little CPU headroom its gateway (2 vCPU
  at 0.7, 80% of its ceiling at 300 req/s) gains, two own-pairs.json list. A read-through cache of
  2 vCPU at 0.7 in one of them saturates at about 528 req/s, at 76% of its ceiling at 400 req/s,
  where it read 935 before its fills counted (1.5.2) and 754 since. An overloaded part's
  utilization falls with its speed (125% where it read 8094%), and with it the busy replicas an
  autoscaled part's cost counts before a run and the analysis's confidence, which weighs each part
  by it. Nothing a run simulates changes.
- **The instant estimate counts a cache's commands as a run does (engine 1.5.2,
  [ADR-0092](docs/adr/0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md)).**
  A sharded cache's CPU was its whole load over one shard's cores, where each shard has its own;
  it is now the share of every shard's cores busy, as a run reports it and as the estimate's
  slots already were. A read-through cache's fill of a key it missed is one more command on the
  shard's executor and cores: it now counts in the cache's slots, its CPU and the queue its
  commands wait in, where it counted only in a read's time. The hot-key feed's six-shard Redis
  reads 4.3% CPU at its 2,000 req/s, beside the 5.3% its run measures, where it read 26%, and
  the design saturates at about 11,217 req/s where it read 7,692; the benchmark designs' Redis
  reads 22.8% and 23.1% busy at the estimate's knee, where it read 19.3% and 17.9%, and neither
  knee moves. A hot key's shard is still busier than the estimate's even spread says. A report's
  `estimate` changes for the same design and seed, so the engine moves from 1.5.1 to 1.5.2;
  nothing a run simulates changes, and its result digest is the same.
- **The instant estimate no longer counts a commit's log flush as database CPU (engine 1.5.1,
  [ADR-0089](docs/adr/0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)).**
  A write waits for the log flush on its connection with no core at work, as a run models it
  and as the sizing counts it. The estimate applied the CPU share to the query and the flush
  together, which filled a database's cores too early wherever it writes. A write still holds
  its connection through the flush, and the flush still counts in the latency; on threads a host
  machine slows, only the query's CPU share takes longer. Black Friday's Orders DB reads 52%
  busy at its 480 req/s peak, as its sizing counts it, where it read 56%, and the starter's
  PostgreSQL 27%, where it read 28%. A report's `estimate` changes for the same design and
  seed, so the engine moves from 1.5.0 to 1.5.1; nothing a run simulates changes, and its result
  digest is the same. On the accuracy benchmarks the estimate's knee for go-mixed moves from
  8,650 to 9,900 req/s (the simulator's is 9,916) and no longer calls Kafka's PostgreSQL
  overloaded; on pg-hot-row, where a row lock the estimate does not model binds, it moves from
  1,712 to 3,000 req/s, further from the measured 947
  ([docs/validation.md](docs/validation.md#against-simple-estimates)).

## [1.3.0] - 2026-11-17

Results that agree with themselves and with the design, after a review of the live demo, and a
freshness objective for asynchronous designs. Results change: the engine moves from 1.1.1 to
1.5.0, so a design simulated again can give other figures. Designs move to schema 1.10: older ones
are upgraded when read, which leaves a third-party API's price of 0 unset (the last entry under
Changed).

### Added

- **A freshness objective, and lost messages (engine 1.4.0, schema 1.10, report 1.19,
  [ADR-0084](docs/adr/0084-judge-asynchronous-designs-by-their-messages-too.md)).** A new
  objective, `messageAgeP95Ms`, judges how old a message is when processed at the design's slowest
  queue, and a run with queues reports its `lostMessages` (dead-lettered or dropped). The
  asynchronous templates set a freshness target: the Kafka order pipeline, whose orders wait
  21.0 s (p95) for a consumer, now reads "No, it doesn't hold up" against its 10 s, where it said
  "Yes, it holds up".

### Changed

- **A third-party API's price says where it came from (engine 1.2.0, schema 1.9, report 1.17,
  [ADR-0081](docs/adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)).**
  `costPerMillionRequests` may be left empty: the API then costs its technology preset's price,
  with a note naming the preset, else it is not priced, and the cost lists it in `unpriced` and
  says the month leaves it out. The app marks the total ("leaves out Payments API (no price)"),
  labels a price "set on the part", "preset price" or "not priced", and no longer says "your
  price" for a figure nobody set, and a price that is a preset's, written on the part or not, is
  labelled the preset's, as a part's hourly price is. The Kafka template's E-mail API bills
  144.92 M calls a month at Amazon SES's list price, $100 a million ($14,492), where it billed
  $0.00; the templates' other free APIs set $0 and say why. The payments and identity-provider
  presets set no price per call.
- **A cache's hit ratio counts the lookups it refused (engine 1.2.1,
  [ADR-0082](docs/adr/0082-count-a-caches-refused-lookups-in-its-hit-ratio.md)).** A read the
  cache turned away, down or by its queue, counts against its hit ratio (`refusedLookups` among
  its counters), and its finding says what became of those reads: the starter losing Redis for
  15 s reports a hit ratio of 57%, 50 reads/s missed and 99 refused, which API Servers read from
  PostgreSQL, where it said 80% and 50 reads/s while PostgreSQL's arrivals rose from 112 to 188
  req/s.
- **A part's shares busy are named, and the estimate is compared like with like (engine 1.3.0,
  report 1.18, [ADR-0083](docs/adr/0083-name-each-utilisation-and-compare-like-with-like.md)).**
  A database of several instances reports its busiest instance's CPU
  (`busiestInstanceCpuUtilization`) and sets its CPU ceiling where that instance fills, where it
  spread every request's CPU time evenly over the primary and its replicas. The Results page sets
  the estimate's CPU against the run's cores busy (it read the slots) at the load the run sent
  the part (it set the peak's against the run's average), and the components table names its
  columns "Slots busy", "CPU busy" and "Ceiling". Black Friday's Orders DB read "56% CPU busy
  (simulated 2%)", "Util 2%" and a ceiling of 2,404 req/s at 303; it now reads "Orders DB's
  busiest instance 35% CPU busy at the 303 req/s the run sent it (simulated 33%; 56% at the
  480 req/s peak)", CPU busy "16% · busiest 33%" and a ceiling of 927 req/s. The dashboard's
  "CPU utilization" panel, which plots the share of a part's own work, is "Own processing".
- **Availability counts lost messages (engine 1.4.0, report 1.19,
  [ADR-0084](docs/adr/0084-judge-asynchronous-designs-by-their-messages-too.md)).** In a run with
  queues, `availability` counts a request that was answered but lost a message it published
  (dead-lettered, or dropped when published) as failed, and the availability objective judges it.
- **A Kafka broker is sized as ArchSim measured it, and idle consumers are named (engine 1.5.0,
  report 1.20,
  [ADR-0085](docs/adr/0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md)).** A Kafka
  broker's size comes from ArchSim's Kafka experiment, a rough fit over the steps the broker kept
  up with: 0.39 fixed cores, 0.053 ms of CPU per message and a 973 MiB working set, listed as
  measured on the assumptions page. The Kafka order pipeline's broker needs 0.65 vCPU and 973 MiB,
  where it said 0.01 vCPU and 11 MiB, on the same kafka.m7g.large. A queue with more consumers
  than partitions reports the idle ones, and its finding says why, in the design review's words:
  "16 of the 32 consumers sat idle: only one consumer per partition is active, so 16 partitions
  keep at most 16 of them busy". It advises adding partitions, never consumers that would sit idle
  too.
- **Advice after a cache's outage asks only for what is missing.** The suggested fix names the
  fallback settings the cache's callers lack; when they already carry on and read the database
  on a miss, it asks for capacity for the fallback's reads, or a replicated cache, where it told
  you to turn on both settings again. The design review does the same for a part that already
  reads the data store on a miss.
- Upgrading a design to schema 1.9 reads a third-party API's price of 0 as unset, unless its
  provenance says a person stated it: saving wrote the old default of 0 out, so it cannot be told
  from a price nobody set. The app reads a project it opens, and a file it imports, the same way.
  Type 0 again for an API that is free.

## [1.2.0] - 2026-11-16

A privacy notice for hosted servers, pages that claim only what is true, and working API docs.
Results do not change: the engine's version (1.1.1) does not move.

### Added

- **A privacy notice for a hosted server.** With `ARCHSIM_PRIVACY_URL`, which the web server
  reads at runtime, the home page's footer and the sign-in page link "Privacy" to it
  (`.env.example`, `deploy/hosted.env.example`).
- **The demo's privacy notice** at [archsim.io/privacy/](https://archsim.io/privacy/), linked from
  the website's footer: who runs app.archsim.io and where, what it stores and for how long, its
  cookies and logs, and how to have your data deleted.

### Fixed

- "Try it out" in the API docs (`/api/v1/docs`) calls the origin the page came from, where it
  called the host the API saw, which behind the web server is an address no browser can reach.
- The API docs name the ArchSim API at ArchSim's version, with what it is, that its results are
  estimates, its license and a link to the guide, where they said "OpenAPI definition" at "v0".
- A hosted server's home page no longer says ArchSim "runs on your machine" with "no account":
  it offers trying it there, or running your own with one command, and "no account needed" only
  where it offers sandboxes. A local install says what it said.
- On a phone, a sandbox's guest is offered the design's JSON to take to a computer, not a link
  that opens only in the browser the sandbox belongs to.
- The accuracy page says near its top that every run was measured on one laptop under Docker
  Desktop, and that the benchmarks rerun when a person starts them (the weekly and release runs
  stay off until a run passes on Linux), where it said they rerun every week. It and the
  website's home page call the experiments small synthetic services on real PostgreSQL, Redis and
  Kafka.
- Smaller things in the app: run pickers name each run's short id; the projects page counts a
  sandbox's projects against its cap ("4 of 5 in Sandbox"); a sandbox's end names your time zone;
  the browser tab names the open project; and Results' queueing-formulas line links "What the
  formulas leave out" instead of a stray "not simulated".
- The website's browser bar takes the colour of the theme it shows, dark unless light is chosen,
  where it followed the system's; and its home page's description fits a search result.
- A finding that counts one request says so: `1 request failed at API`, `1 client request failed
  because of them`, `refused 1 call`, where it said `1 requests`. Only words change; the engine
  stays at 1.1.1.
- The landing page's "Decide" figures and the hero's p95 goal are a real run's of Black Friday on
  Kubernetes, which the engine's tests keep current: $1,434 a month where it said $1,278, and the
  template's 500 ms goal where the hero said 300.

## [1.1.0] - 2026-11-16

Host ArchSim on a public server of your own, and let visitors try it there without an account.

### Added

- **A kit for hosting a public server**, in `deploy/`: the release's images with sign-in behind
  Caddy, which serves HTTPS with certificates it renews itself, or a Cloudflare Tunnel, which opens
  no port; a nightly database backup; and run limits for a public demo, on any server with Docker
  from 2 cores and 4 GB, amd64 or arm64.
  [Hosting a public server](docs/deployment.md#hosting-a-public-server) goes from an empty server to a
  running site, and from one release to the next; an optional workflow deploys the project's own
  demo over SSH. A release now also checks that the kit runs its images.
- **Try it without signing in.** With `ARCHSIM_SANDBOXES=true` on a hosted server, the sign-in
  page offers a sandbox for 24 hours with no account: a guest owns a sandbox workspace (five
  projects, small runs) until the API deletes it with everything in it. `POST /auth/sandbox`
  starts one; `GET /auth/config` and `GET /auth/me` say whether sandboxes are offered and when a
  guest's sandbox goes, which the app shows under its header. Starts are limited per address and
  to 500 at once; guests make no team workspaces, API tokens, invitations or share links
  ([ADR-0079](docs/adr/0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)).

### Changed

- The database gains its second migration (V2: `users.expires_at`), so the API migrates it
  before workers of this release start.
- The accuracy page links the files it names (the validation report, the calibration guide, the
  parity report, the benchmark results and their workflow) to the repository on GitHub.

### Fixed

- On a phone, the home page's feature grid fits the screen instead of cutting its tiles' right
  edges.

## [1.0.0] - 2026-11-15

The first public release. ArchSim predicts how a system design behaves under load, failures and
cost, and publishes how accurate it is. Start with [Getting started](docs/getting-started.md).

### Highlights

- **A studio for distributed systems.** Four workspaces around one canvas (Design, Test, Results
  and a Dashboard) and one Simulate button. 25 ready-made templates, from a cache-aside starter to
  Kafka pipelines, Cassandra quorums and serverless bursts.
- **Real technology behaviour.** PostgreSQL, MySQL, SQL Server, MongoDB, Cassandra, DynamoDB-style
  stores, Elasticsearch, Redis, Valkey, Memcached, Kafka, RabbitMQ, SQS-style queues, Nginx, Envoy,
  Kong, Kubernetes, serverless functions and third-party APIs, each with the behaviour its
  documentation describes and a link to it. A core set is ArchSim's focus; the rest is marked
  experimental.
- **A deterministic Java engine.** A discrete-event simulator on Java 25 moves every request
  through every hop, queue, lock and retry. The same document and seed give the same result digest
  in the web app, the API, the CLI and the MCP server.
- **A straight answer.** A verdict against your objectives, the bottleneck, what to fix first, the
  breaking point and the monthly cost on real AWS instance types.
- **Published accuracy.** [docs/validation.md](docs/validation.md) checks predictions made before
  measuring against nine experiments on real services, misses included.
- **One command to run it.** `docker compose up` starts the whole stack from ArchSim's release
  images, for amd64 and arm64, with nothing but Docker installed.

### Added

- **Design:** a parts library with technology logos; mobile, web and API-consumer clients with
  their own timeouts, retries and traffic shares; CPU, memory and storage per part, auto-sized for
  the peak load when left empty; a design review for single points of failure, missing timeouts,
  retry amplification, timeout inversions and pool exhaustion.
- **Test:** a test plan with traffic, objectives and failures (outages, slowdowns, link latency,
  spikes), chaos pads, and a second-by-second replay on the canvas and the timeline.
- **Results:** the answer, a cost-against-latency comparison of every scenario, a breaking-point
  sweep, findings in plain language, the calculations behind each number, sampled request traces
  and a Markdown report.
- **Dashboard:** Grafana-style panels with a synced crosshair, per-component signals, annotations
  and data inspection.
- **Calibration** from Prometheus metrics or an OpenTelemetry trace export, with every fitted input
  marked measured and a replay of what was observed ([docs/calibration.md](docs/calibration.md)).
- **Import** from docker-compose, Kubernetes manifests or a Terraform plan, and **export** to
  docker-compose, Kubernetes manifests or Mermaid ([docs/import.md](docs/import.md)).
- **Prices** from an AWS us-east-1 on-demand snapshot: parts run on the cheapest real instance
  type that fits, and every cost line cites it ([docs/pricing.md](docs/pricing.md)).
- **An SLO gate for CI:** `archsim check` and a GitHub Action that comments on pull requests
  ([docs/ci.md](docs/ci.md)).
- **The `archsim` command line:** validate, analyze, run, compare, calibrate, import, export,
  check, catalog and schema ([archsim-cli](archsim-cli/README.md)).
- **AI agents:** an MCP server over stdio for coding agents, and WebMCP tools in the web app for
  agents in the browser ([docs/agents.md](docs/agents.md)).
- **Teams (optional):** GitHub and Google sign-in, personal and team workspaces with viewer, editor
  and owner roles, invitations, API tokens, rate limits, comments, sign-offs, a history of every
  change and read-only share links ([docs/deployment.md](docs/deployment.md)).
- **Peak hours a month (schema 1.8).** `workload.peakHoursPerMonth` says how many hours a month
  the traffic runs at its pattern's peak; the monthly cost bills those hours at the peak and the
  rest at the base load, line by line, and the cost per million requests counts both loads'
  requests; `cost.peakHours` reports the hours it billed. It defaults to 0 (the whole month at
  the base load), is left out of a document while 0, and is set in Design → Traffic for a burst, a
  ramp or a trace ([ADR-0074](docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)).
- **ArchSim's own version**, 1.0.0, kept apart from the engine's: `archsim --version` prints
  `archsim 1.0.0, engine 1.1.1`, `GET /meta` gives `version` beside `engineVersion`, and the MCP
  server's `serverInfo.version` is ArchSim's
  ([ADR-0076](docs/adr/0076-version-archsim-apart-from-its-engine.md)).
- **Running it:** a Docker Compose stack (PostgreSQL, migrations, the API, workers and the web
  app) from release images on GitHub's container registry, `ghcr.io/dante0747/archsim-app` and
  `archsim-web`, for amd64 and arm64; `make up-local` runs your own build instead
  ([ADR-0077](docs/adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)). Workers
  run on any number of machines, sharing a run queue in PostgreSQL.

### Changed

- The engine, the API, the CLI and the MCP server moved from Python to Java 25
  ([the port's plan](docs/proposals/java-25-port.md)). The Java engine 1.0.0 reproduces the
  frozen Python engine 0.16.0's recorded runs figure for figure
  ([java-parity.md](docs/validation/java-parity.md)), about 14 times faster on the templates
  ([docs/performance.md](docs/performance.md)).
- **Cost of autoscaling (engine 1.1.0, report 1.16).** An autoscaled service is billed at the
  replicas its autoscaler settles at for the base load's work, within `minReplicas`–`maxReplicas`,
  no longer at the run's average replicas scaled to the base load and floored at the count it
  starts with; a Kubernetes cluster with its autoscaler is billed the nodes its pods need there,
  and one without it its `nodes`. Beside the month, every cost prices an hour at the peak
  (`cost.hourly`), with each line's instances there (`peakInstances`)
  ([ADR-0074](docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md),
  [What a month is](docs/pricing.md#what-a-month-is)).
- **Black Friday's sale day.** The Black Friday on Kubernetes template bills 24 peak hours a
  month, and a new scenario, "No autoscaling (14 fixed pods)", sizes the checkout for the peak all
  month: $843.63 a month with the HPA against $1,434.02 for 14 fixed pods. The templates hold 125
  scenarios.
- The accuracy benchmarks' harness and the repository's tools moved from Python to Java as well:
  [archsim-benchmarks](archsim-benchmarks/README.md), run as
  `./gradlew :archsim-benchmarks:run --args="<command>"`, and [archsim-tools](archsim-tools/README.md)
  (the commit-message check, CI's changed parts and the price refresh). Building ArchSim and
  contributing to it no longer need Python or uv ([CONTRIBUTING.md](CONTRIBUTING.md#development-setup)).
  The one Python left is a benchmarked service, `benchmarks/service-python`, which builds in its
  own container.
- ArchSim is licensed under the GNU AGPL-3.0-only, with a commercial license available from the
  copyright holder ([ADR-0067](docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license.md)).

### Fixed

- **The base load's rates after a run (engine 1.1.1).** When no time bucket of a run's window is
  at the base load (a ramp that passes it during the warm-up), the base's per-request rates come
  from the buckets nearest it, no longer from the whole window's mean scaled down, which the
  failures and timeouts at the peak moved. The peak's rates do the same when no bucket is at the
  peak. No template's monthly cost changes.
- **Labels on the Cost vs. speed map.** A run's label no longer covers another run's marker or
  label, or the objective line's label: it moves to its point's other side, or a line down or up
  with a leader to its point. Runs drawn at one point share one label that names each.

### Removed

- The Python engine, its API and its MCP server. The tag `engine-0.16.0` keeps the engine and the
  MCP server.
- The SQLite development mode: the API needs PostgreSQL
  ([ADR-0016](docs/adr/0016-drop-sqlite-development-mode.md)).
- Plugin entry points for third-party component models.
- The Python benchmark harness and the repository's Python scripts. The tag `cpython-recorders`
  keeps them.
- **Breaking:** the CLI's hidden `archsim functions` command, which answered the Python benchmark
  harness's calls into the engine. A script that called it now gets an unknown command (exit
  code 2); call the engine's classes in process instead, as `archsim-benchmarks` does
  ([ADR-0073](docs/adr/0073-remove-archsim-functions-with-the-python-harness.md)).

### Upgrading from a pre-release

- A database below the Alembic revision 0008 upgrades from the tag `alembic-0008` first; Flyway
  takes it from there ([ADR-0063](docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)).
- An MCP client set up with `uv run --directory /path/to/archsim archsim-mcp` needs the Java
  server's command instead ([docs/agents.md](docs/agents.md)).
- `docker compose up` runs the release images and builds nothing: run your own build with
  `make image` and `make up-local` (`docker-compose.local.yml`) instead of
  `docker compose up --build` ([docs/getting-started.md](docs/getting-started.md#your-own-build)).
- The `commit-msg` hook checks messages with Java's source launcher and needs JDK 22 or later: it
  runs `$JAVA_HOME`'s Java when that is set, else `java` on the `PATH`
  ([CONTRIBUTING.md](CONTRIBUTING.md#development-setup)).

### Known limitations

- No technology counts as validated yet. On the five held-out experiments the median error in the
  predicted breaking point ran from -6% to +70%, and four of the five over-predicted capacity
  ([docs/validation.md](docs/validation.md)).
- Not modeled: OOM kills and restarts, bandwidth and packet loss, deadlocks and range locks,
  random failures (faults are scheduled), parallel fan-out calls and regions
  ([docs/simulation-model.md](docs/simulation-model.md#what-is-not-modeled)).
- Sign-in is covered by automated tests but has not been tried with real GitHub or Google OAuth
  apps yet.
- Calibration has been checked on ArchSim's own fixtures, not yet against a live Prometheus or a
  real OpenTelemetry export.
- Exports are scaffolding to review, not production configuration.
- The editor is designed for desktop screens.

[Unreleased]: https://github.com/dante0747/archsim/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/dante0747/archsim/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/dante0747/archsim/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/dante0747/archsim/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/dante0747/archsim/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/dante0747/archsim/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/dante0747/archsim/releases/tag/v1.0.0
