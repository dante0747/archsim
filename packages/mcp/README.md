# archsim-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) server for ArchSim over stdio, so AI
coding agents (Claude Code, Claude Desktop, Cursor and others) can design, validate, analyze,
simulate, compare, find the breaking point of, export and calibrate architectures with the
ArchSim engine. No browser, API server or network is needed.

```bash
claude mcp add archsim -- uv run --directory /path/to/archsim archsim-mcp
```

Tools, resources and limits: [docs/agents.md](../../docs/agents.md).
