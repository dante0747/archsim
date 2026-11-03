"""Background simulation runs: a queue in the database and the workers that drain it.

- ``queue``: the run queue every process shares through the database (claims, leases, cancel
  requests, reaping);
- ``worker``: the supervisor that takes runs and simulates each in its own process;
- ``child``: what runs inside a simulation process;
- ``orphans``: keeps simulation processes from outliving their worker.

The API (``archsim-app``, in Java) only queues runs and asks for cancels, in the database;
clients poll ``GET /runs/{id}``. Nothing here is imported eagerly, so a simulation process loads
only ``child`` and the engine.
"""
