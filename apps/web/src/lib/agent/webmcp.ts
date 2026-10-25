/**
 * WebMCP: lets AI agents in the browser use ArchSim through tools the page registers, instead of
 * clicking through the interface (https://webmachinelearning.github.io/webmcp/).
 *
 * The draft standard exposes `document.modelContext.registerTool(tool, { signal })`: aborting
 * the signal unregisters the tool, and whatever `execute` resolves to is passed to the agent as
 * JSON. Chrome's early preview and the community polyfills expose `navigator.modelContext`
 * instead and unregister with `unregisterTool(name)`; both are supported. Without either,
 * nothing happens. Tools only act on this page's own state, through the same store actions as
 * the interface (so edits can be undone); there is no code execution.
 */

export interface ToolAnnotations {
  /** The tool only reads data. */
  readOnlyHint?: boolean;
  /** The tool has effects beyond this page that are hard to undo (e.g. saving). */
  consequentialHint?: boolean;
}

export interface AgentTool {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations?: ToolAnnotations;
  /** Resolves to a JSON-serializable result; throw an Error to report a problem to the agent. */
  execute: (input: Record<string, unknown>) => Promise<unknown>;
}

/** What a tool call returns to the agent: MCP-style text content (valid JSON in every version). */
export interface ToolResult {
  content: { type: "text"; text: string }[];
  isError?: boolean;
}

interface ModelContextLike {
  registerTool: (tool: Record<string, unknown>, options?: { signal?: AbortSignal }) => unknown;
  unregisterTool?: (name: string) => unknown;
}

/** The browser's model context, if it has one. */
export function modelContext(): ModelContextLike | null {
  if (typeof window === "undefined") return null;
  const d = (document as unknown as { modelContext?: ModelContextLike }).modelContext;
  if (d && typeof d.registerTool === "function") return d;
  const n = (navigator as unknown as { modelContext?: ModelContextLike }).modelContext;
  if (n && typeof n.registerTool === "function") return n;
  return null;
}

export function toResult(value: unknown): ToolResult {
  return { content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value, null, 2) }] };
}

export function toError(e: unknown): ToolResult {
  return { content: [{ type: "text", text: `Error: ${e instanceof Error ? e.message : String(e)}` }], isError: true };
}

/** Called after every tool call (the editor shows agent edits as toasts). */
export type ToolCallListener = (tool: AgentTool, input: Record<string, unknown>, outcome: { ok: boolean; result: unknown }) => void;

async function registerOne(mc: ModelContextLike, tool: AgentTool, signal: AbortSignal, onCall?: ToolCallListener): Promise<void> {
  const definition = {
    name: tool.name,
    title: tool.title,
    description: tool.description,
    inputSchema: tool.inputSchema,
    annotations: tool.annotations ?? {},
    execute: async (input: unknown): Promise<ToolResult> => {
      const args = input && typeof input === "object" ? (input as Record<string, unknown>) : {};
      try {
        const result = await tool.execute(args);
        onCall?.(tool, args, { ok: true, result });
        return toResult(result);
      } catch (e) {
        onCall?.(tool, args, { ok: false, result: e });
        return toError(e);
      }
    },
  };
  const attempt = async () => {
    const handle = (await mc.registerTool(definition, { signal })) as { unregister?: () => void } | undefined;
    // older shapes: a handle to unregister, or unregisterTool(name); the draft unregisters on abort
    signal.addEventListener(
      "abort",
      () => {
        try {
          handle?.unregister?.();
          mc.unregisterTool?.(tool.name);
        } catch {
          // already gone
        }
      },
      { once: true },
    );
  };
  try {
    await attempt();
  } catch (e) {
    // a stale registration with the same name (hot reload, an earlier mount): replace it once
    if (mc.unregisterTool && !signal.aborted) {
      try {
        mc.unregisterTool(tool.name);
        await attempt();
        return;
      } catch {
        // fall through
      }
    }
    if (!signal.aborted) console.debug(`WebMCP: could not register ${tool.name}`, e);
  }
}

/**
 * Register tools with the browser's model context while `signal` is not aborted. A polyfill may
 * install itself after the page loads, so the context is looked for again a few times.
 */
export function registerAgentTools(tools: AgentTool[], signal: AbortSignal, onCall?: ToolCallListener): void {
  if (typeof window === "undefined") return;
  const delays = [0, 500, 2000, 5000];
  let i = 0;
  const tryNow = () => {
    if (signal.aborted) return;
    const mc = modelContext();
    if (mc) {
      for (const t of tools) void registerOne(mc, t, signal, onCall);
      return;
    }
    if (++i < delays.length) setTimeout(tryNow, delays[i] - delays[i - 1]);
  };
  tryNow();
}
