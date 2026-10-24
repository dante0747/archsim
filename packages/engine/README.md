# archsim-engine

The simulation core of ArchSim: the versioned architecture document schema, the component
registry, the SimPy-based discrete-event engine, metrics collection, bottleneck analysis and
scenario comparison. It has **no dependency on the web API** and can be used from Python or the
`archsim` CLI:

```bash
uv run archsim validate examples/cache-aside-starter.json
uv run archsim run examples/cache-aside-starter.json --scenario three-api-replicas
```

See `docs/simulation-model.md` at the repository root for the modeling assumptions.
