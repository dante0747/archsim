"""Background simulation runs: a queue in the database and the workers that drain it.

- ``queue``: the run queue every process shares through the database (claims, leases, cancel
  requests, reaping);
- ``worker``: the supervisor that takes runs and simulates each in its own process, and
  ``JobService``, the API's handle on both;
- ``child``: what runs inside a simulation process;
- ``orphans``: keeps simulation processes from outliving their worker.

HTTP requests only enqueue; clients poll ``GET /runs/{id}``. Nothing here is imported eagerly,
so a simulation process loads only ``child`` and the engine.
"""
