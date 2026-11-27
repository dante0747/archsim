"use client";

/**
 * Registers ArchSim's WebMCP tools while mounted: the app tools on every page, the design tools
 * while the editor is open. Agent edits and runs show as toasts, so the user sees what changed.
 */
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { toast } from "sonner";
import { appTools } from "@/lib/agent/app-tools";
import { editorTools } from "@/lib/agent/editor-tools";
import { type ToolCallListener, registerAgentTools } from "@/lib/agent/webmcp";
import { leave } from "@/lib/leave";

const announce: ToolCallListener = (tool, _input, outcome) => {
  if (tool.annotations?.readOnlyHint && tool.name !== "open_project") return;
  if (!outcome.ok) return; // the agent gets the error and can try again
  const summary = (outcome.result as { summary?: unknown } | null)?.summary;
  toast.message("AI agent", { description: typeof summary === "string" ? capitalize(summary) : tool.title });
};

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function AppAgentTools() {
  const router = useRouter();
  useEffect(() => {
    const ac = new AbortController();
    registerAgentTools(
      appTools((path) => leave(() => router.push(path))),
      ac.signal,
      announce,
    );
    return () => ac.abort();
  }, [router]);
  return null;
}

export function EditorAgentTools() {
  useEffect(() => {
    const ac = new AbortController();
    registerAgentTools(editorTools(), ac.signal, announce);
    return () => ac.abort();
  }, []);
  return null;
}
