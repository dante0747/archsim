"""Write SimPy's order of events on small programs for the Java kernel, or check the committed file.

The Java kernel keeps SimPy 4.1.2's order of events: by time, then priority (a process starts
URGENT, everything else is NORMAL), then the order events were scheduled in, with SimPy's
conditions, resource queues and stores. Each program below exercises one part of that order and
writes what happened, in order, as ``[time, what]`` lines; ``TimingCasesTests`` and ``QueueCasesTests`` run the same
program on the Java kernel and expects the same lines. Times are written as Python's
``float.hex()``. Each program also records how many events SimPy processed, and the chunked
run (``engine._run_until``) how many each chunk counted.

Keep each program here and its Java twin in ``TimingCasesTests`` or ``QueueCasesTests`` side by side:
the test names the program it mirrors.

Usage:
    uv run python scripts/export_kernel_cases.py           # write the cases
    uv run python scripts/export_kernel_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Callable, Generator
from pathlib import Path
from typing import Any

import simpy

from archsim_engine.engine import _run_until

GENERATOR = f"scripts/export_kernel_cases.py (SimPy {simpy.__version__}, archsim_engine 0.16.0)"
OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/kernel/cases.json"
)
Trace = list[list[Any]]
Gen = Generator[simpy.Event, Any, Any]


class Recorder:
    """What a program saw, in order, with the time it saw it."""

    def __init__(self, env: simpy.Environment) -> None:
        self.env = env
        self.lines: Trace = []

    def __call__(self, what: str) -> None:
        self.lines.append([float(self.env.now).hex(), what])


def run_all(env: simpy.Environment) -> int:
    """Run to the end, counting the events processed."""
    count = 0
    while True:
        try:
            env.step()
        except simpy.core.EmptySchedule:
            return count
        count += 1


def timeouts_and_starts(env: simpy.Environment, log: Recorder) -> None:
    """Processes start before timeouts due at the same time; equal times keep their order."""

    def sleeper(name: str, delays: list[float]) -> Gen:
        log(f"{name} starts")
        for d in delays:
            yield env.timeout(d)
            log(f"{name} woke after {d}")

    env.timeout(0)
    env.process(sleeper("a", [0.1, 0.2, 0.0]))
    env.process(sleeper("b", [0.3, 0.0]))
    env.process(sleeper("c", [0.30000000000000004, 0.1]))


def nested_waits(env: simpy.Environment, log: Recorder) -> None:
    """A process waits on another's end; an event already processed is passed straight through."""

    def child(name: str, d: float) -> Gen:
        yield env.timeout(d)
        log(f"{name} done")
        return name

    def parent() -> Gen:
        early = env.event()
        early.succeed("early")
        log("parent waits for the early event")
        yield env.timeout(1)
        value = yield early
        log(f"parent got {value} without waiting")
        first = env.process(child("first", 0.5))
        got = yield first
        log(f"parent got {got}")
        second = env.process(child("second", 0))
        yield env.timeout(0)
        log("parent past a zero timeout")
        got = yield second
        log(f"parent got {got}")

    env.process(parent())


def resource_contention(env: simpy.Environment, log: Recorder) -> None:
    """Two slots, six users arriving together and later; a release grants one waiter."""
    res = simpy.Resource(env, capacity=2)

    def user(name: str, arrive: float, hold: float) -> Gen:
        if arrive:
            yield env.timeout(arrive)
        tok = res.request()
        log(f"{name} asks, triggered={tok.triggered} count={res.count} queue={len(res.queue)}")
        yield tok
        log(f"{name} holds")
        yield env.timeout(hold)
        res.release(tok)
        log(f"{name} releases, count={res.count} queue={len(res.queue)}")

    for i, (arrive, hold) in enumerate([(0, 1), (0, 2), (0, 1), (0, 0.5), (1, 1), (1.5, 0.25)]):
        env.process(user(f"u{i}", arrive, hold))


def request_grants_oldest(env: simpy.Environment, log: Recorder) -> None:
    """A release frees a slot at once, but waiters are granted when its event is processed; a
    request made in between grants the oldest waiter, not itself."""
    res = simpy.Resource(env, capacity=1)

    def holder() -> Gen:
        tok = res.request()
        yield tok
        log("holder holds")
        yield env.timeout(1)
        res.release(tok)
        log(f"holder released, count={res.count} queue={len(res.queue)}")
        late = res.request()
        log(f"holder asks again: triggered={late.triggered} queue={len(res.queue)}")
        yield late
        log("holder holds again")
        res.release(late)

    def waiter(name: str) -> Gen:
        tok = res.request()
        yield tok
        log(f"{name} holds")
        yield env.timeout(0.5)
        res.release(tok)
        log(f"{name} releases")

    env.process(holder())
    env.process(waiter("w1"))
    env.process(waiter("w2"))


def queue_timeouts(env: simpy.Environment, log: Recorder) -> None:
    """Waiting for a slot or a timeout, as a worker pool waits: the request is cancelled when the
    timeout wins, and a grant and a timeout due together go to whichever was scheduled first."""
    res = simpy.Resource(env, capacity=1)

    def user(name: str, arrive: float, hold: float, patience: float) -> Gen:
        if arrive:
            yield env.timeout(arrive)
        tok = res.request()
        if not tok.triggered:
            yield tok | env.timeout(patience)
            if not tok.triggered:
                tok.cancel()
                log(f"{name} gave up, queue={len(res.queue)}")
                return
        log(f"{name} holds")
        yield env.timeout(hold)
        res.release(tok)
        log(f"{name} releases")

    env.process(user("a", 0, 2, 1))
    env.process(user("b", 0, 1, 1))
    env.process(user("c", 0, 1, 2))
    env.process(user("d", 0.5, 1, 1.5))
    env.process(user("e", 1, 1, 5))


def stores(env: simpy.Environment, log: Recorder) -> None:
    """Getters wait for items; a put serves the oldest getter when its event is processed, and a
    get made in between serves the oldest waiting getter, not itself."""
    store: simpy.Store = simpy.Store(env)

    def getter(name: str, arrive: float) -> Gen:
        if arrive:
            yield env.timeout(arrive)
        item = yield store.get()
        log(f"{name} got {item}")

    def putter() -> Gen:
        yield env.timeout(1)
        store.put("x")
        log(f"put x, items={len(store.items)}")
        late = store.get()
        log(f"late get triggered={late.triggered}")
        yield env.timeout(1)
        store.put("y")
        store.put("z")
        log(f"put y and z, items={len(store.items)}")
        got = yield late
        log(f"late got {got}")
        store.put("w")
        log(f"put w, items={len(store.items)}")

    env.process(getter("g1", 0))
    env.process(getter("g2", 0))
    env.process(putter())
    env.process(getter("g3", 3))


def conditions(env: simpy.Environment, log: Recorder) -> None:
    """Any-of and all-of: a condition fires once, from events already processed or still to come,
    and an empty all-of fires at once."""

    def child(name: str, d: float) -> Gen:
        yield env.timeout(d)
        log(f"{name} done")

    def parent() -> Gen:
        empty = env.all_of([])
        yield empty
        log("empty all-of passed")
        procs = [env.process(child(f"c{i}", d)) for i, d in enumerate([0.5, 0.0, 0.25])]
        yield env.all_of(procs)
        log("all children done")
        a = env.timeout(1)
        b = env.timeout(1)
        yield a | b
        log(f"any-of fired, a={a.processed} b={b.processed}")
        done = env.event()
        yield env.timeout(0)
        done.succeed()
        yield env.timeout(0)
        yield done | env.timeout(3)
        log("any-of of a processed event passed")
        late = env.timeout(2)
        yield env.any_of([env.timeout(1), late])
        log(f"any-of fired before the late timeout, late={late.processed}")
        yield late
        log("late timeout seen")

    env.process(parent())


def nested_conditions(env: simpy.Environment, log: Recorder) -> None:
    """An any-of over an all-of: when the outer condition is processed it stops the inner one
    counting too, so the inner condition never triggers and its waiter never resumes."""
    a = env.timeout(2)
    b = env.timeout(3)
    x = env.timeout(1)
    inner = env.all_of([a, b])

    def waiter() -> Gen:
        yield inner
        log("inner waiter resumed")

    def parent() -> Gen:
        yield env.any_of([inner, x])
        log(f"outer fired, inner triggered={inner.triggered}")
        yield env.timeout(5)
        log(f"later, inner triggered={inner.triggered} a={a.processed} b={b.processed}")

    env.process(waiter())
    env.process(parent())


def chunks(env: simpy.Environment, log: Recorder) -> list[int]:
    """The simulator's chunked run: stop events at each chunk's end, rescheduled first in the
    next, a check every few events."""

    def ticker(name: str, every: float) -> Gen:
        while True:
            yield env.timeout(every)
            log(f"{name} tick")

    checks: list[float] = []

    def check() -> None:
        checks.append(env.now)
        log("check")

    env.process(ticker("fast", 0.3))
    env.process(ticker("slow", 1.1))
    counts = []
    t = 0.0
    end = 3.0
    step = end / 4
    while t < end:
        t = min(t + step, end)
        if t > env.now:
            counts.append(_run_until(env, t, 3, check))
        log(f"chunk to {t}")
    return counts


PROGRAMS: dict[str, Callable[[simpy.Environment, Recorder], Any]] = {
    "timeoutsAndStarts": timeouts_and_starts,
    "nestedWaits": nested_waits,
    "resourceContention": resource_contention,
    "requestGrantsOldest": request_grants_oldest,
    "queueTimeouts": queue_timeouts,
    "stores": stores,
    "conditions": conditions,
    "nestedConditions": nested_conditions,
}


def export() -> str:
    """The cases file's text."""
    cases: dict[str, Any] = {"generator": GENERATOR}
    for name, program in PROGRAMS.items():
        env = simpy.Environment()
        log = Recorder(env)
        program(env, log)
        events = run_all(env)
        cases[name] = {"trace": log.lines, "events": events}
    env = simpy.Environment()
    log = Recorder(env)
    counts = chunks(env, log)
    cases["chunks"] = {"trace": log.lines, "counts": counts}
    return json.dumps(cases, indent=1) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_kernel_cases.py")
            return 1
        print(f"{OUT.name} matches SimPy's order of events")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
