---
status: proposed
date: 2026-11-28
decision-makers: Majid Abarghooei (owner)
---

# 0116. Give the hosting kit's API a 1 GB heap

## Context and Problem Statement

The hosting kit sized its JVMs for the smallest server it fits, 2 cores and 4 GB: the API had a
512 MB heap, and the containers' memory limits added up to 3.3 GB
([ADR-0080](0080-host-a-public-server-from-a-compose-kit.md)). The engine's routes answer as many
requests at once as the API's heap holds, 125 MB a request at the 5 MB body cap; on that heap, with
one calibration replay slot on two cores, that is one
([ADR-0108](0108-bound-the-engines-routes-by-slots-per-person-limits-and-a-sandbox-body-cap.md)).

The editor now saves a design after each pause in editing
([ADR-0113](0113-save-a-design-as-it-is-edited-and-on-the-way-out-of-the-editor.md)), and each pause
asks the engine's routes to validate, analyze and save it. With one slot, a second visitor's
request waits up to a second for it, and gets 503 if it is still held then. The public server runs
the kit on 2 cores and 4 GB.

How many of the engine's requests should the kit's API answer at once, and with what memory?

## Decision Drivers

- A few people editing at once on the smallest server never meet a 503 for an ordinary save.
- The engine's requests stay bounded by the heap that holds them, as ADR-0108 bounds them.
- The kit's memory limits still fit the smallest server it is meant for.

## Considered Options

- Give the API a 1 GB heap: five of the engine's requests at once on two cores
- Keep 512 MB and set `ARCHSIM_MAX_ENGINE_REQUESTS` in the kit to more than the heap holds by
  ADR-0108's budget (125 MB a request at the 5 MB body cap, where 77 MB was measured and an
  editor's design is kilobytes)
- Keep 512 MB and lower the kit's body cap (1 MB gives nine requests at 512 MB)
- Take the memory from the worker

## Decision Outcome

Chosen option: "give the API a 1 GB heap", the owner's choice, because it is the only option that
keeps both of ADR-0108's promises, that every request the heap admits fits in it and that imports
of up to 5 MB still work, and it costs the worker nothing; it meets the third driver only narrowly
(see Consequences). The kit's defaults become `ARCHSIM_API_HEAP=1g` and `ARCHSIM_API_MEMORY=1408m`
(a JVM's limit is its heap and 384 MB, as before). On two cores that heap holds five of the
engine's requests beside one replay slot; on four, where three replay slots take their share, the
guide gives the API 1,536 MB, which holds eight. A `hosted.env` that sets both settings keeps its
own.

This amends ADR-0080's "Sized for 2 cores and 4 GB" (the API's heap and the limits' sum) and
ADR-0108's figures for the kit (one slot on its heap).

### Consequences

- Good, because five people can save at the same moment on the smallest server before one waits.
- Good, because ADR-0108's bound holds as it was: every request the heap admits fits in it.
- Bad, because the containers' limits now add up to 3.8 GB on a 4 GB server, about all of its
  memory once the kernel and Docker take theirs, and `update.sh` runs the migration's container
  (768 MB) beside them. Limits are ceilings, not use: the public server, at 2 cores and 3.9 GB with
  7.9 GB of swap, showed 2.5 GB available and its containers at 0.7 GB before the change. There a
  peak in every container at once would reach the swap; on a 4 GB server without swap, as a fresh
  cloud server often is, the kernel would end the largest JVM instead, so the guide asks for a swap
  file there, or the old 512 MB and one slot.
- Neutral, because a server that set the API's heap or memory in `hosted.env` keeps what it set; one
  that set only the memory below 1.4 GB gets a 1 GB heap in it, so set both or neither.

### Confirmation

`ComposeFileTests` pins the API's defaults and checks that every JVM's memory limit is its heap and
384 MB; `ArchsimPropertiesTests` holds the slots a 1 GB heap gives on two cores. After the deploy,
`free -m` and `docker stats` on the public server, at a busy hour, show the headroom left.

## Pros and Cons of the Options

### Keep 512 MB and set more requests than the heap holds

- Good, because the kit's memory stays as it was.
- Bad, because five 5 MB imports at once need about 385 MB, as measured, besides the API's 256 MB,
  more than the 495 MB a 512 MB heap gives; the API would exit on running out of memory
  ([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)).

### Keep 512 MB and lower the body cap to 1 MB

- Good, because nine requests fit at 512 MB.
- Bad, because it refuses the Terraform and Kubernetes imports the 5 MB cap is there for
  (ADR-0108).

### Take the memory from the worker

- Good, because the containers' limits keep their sum.
- Bad, because the worker's 1 GB is what two runs of 1,000,000 requests need by archsim-app's rule
  (384 MB each and 256 MB besides): any memory taken from it costs a run at once.

## More Information

- [ADR-0080](0080-host-a-public-server-from-a-compose-kit.md) and
  [ADR-0108](0108-bound-the-engines-routes-by-slots-per-person-limits-and-a-sandbox-body-cap.md),
  which this amends.
- [docs/deployment.md](../deployment.md), "1. A server", has the sizes for each server.
