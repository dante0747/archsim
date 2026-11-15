# @archsim/web

The ArchSim editor: a Next.js 16 app (React Flow canvas, Zustand stores, Tailwind CSS v4,
Radix primitives, Recharts, elkjs). It talks to the API (`archsim-app`) through a same-origin proxy
(`src/app/api/[...path]/route.ts`) that forwards to `ARCHSIM_API_URL`
(default `http://127.0.0.1:8000`). The API's protected resource metadata,
`/.well-known/oauth-protected-resource/**`, goes the same way
(`src/app/.well-known/oauth-protected-resource/[[...path]]/route.ts`); nothing else under
`/.well-known` does.

Start PostgreSQL, the API and a worker first, as
[Run from source](../../docs/getting-started.md#run-from-source) describes, then:

```bash
npm ci
npm run dev        # http://localhost:3000
npm test           # unit tests (Vitest + Testing Library)
npm run lint
npm run build      # then: npm run typecheck
npm run e2e        # end-to-end main path against the build (Playwright; see e2e/)
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
  (regenerate with `./gradlew :archsim-engine:generate` from the repo root)
- `*.site.tsx`, `*.site.ts`, `src/components/site/`, `src/lib/site/` - the public website,
  [archsim.io](https://archsim.io): a second, static build of this app (`npm run build:site`), which
  the app's own build ignores; see [docs/website.md](../../docs/website.md)

Inspector forms are generated from the component catalog (`GET /api/v1/components`), so new
component types need no frontend changes. See the root [README](../../README.md).
