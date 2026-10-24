# @archsim/web

The ArchSim editor: a Next.js 16 app (React Flow canvas, Zustand stores, Tailwind CSS v4,
Radix primitives, Recharts, elkjs). It talks to the FastAPI service through a same-origin proxy
(`src/app/api/[...path]/route.ts`) that forwards to `ARCHSIM_API_URL`
(default `http://127.0.0.1:8000`).

```bash
npm ci
npm run dev        # http://localhost:3000 (start the API first: uv run archsim-api serve)
npm test           # unit tests (Vitest + Testing Library)
npm run lint
npm run build      # then: npm run typecheck
```

Layout:

- `src/lib/` - document/graph conversion, connection rules, layout, API client, types
- `src/store/` - editor state (graph, undo/redo, validation) and runs (polling, reports, compare)
- `src/components/editor/` - canvas, nodes, edges, component rail and library, toolbar, workflow
  bar, command palette, status bar, inspector, traffic and scenarios
- `src/components/shapes.tsx` - the schematic shape of each component kind (nodes, rail, previews)
- `src/components/results/` - results panel: insights, timeline charts, components, compare,
  history, assumptions
- `src/test/fixtures/` - catalog and example used by unit tests
  (regenerate with `uv run python scripts/export_web_fixtures.py` from the repo root)

Inspector forms are generated from the component catalog (`GET /api/v1/components`), so new
component types need no frontend changes. See the root [README](../../README.md).
