import { Box, Database, Server, Split, Users, Zap } from "lucide-react";
import { type Kind, kindColor, kindSoft } from "@/lib/theme";
import { cn } from "@/lib/utils";

/** Icon for a catalog icon name (unknown names fall back to a box). */
export function ComponentIcon({ name, className }: { name: string | undefined; className?: string }) {
  switch (name) {
    case "users":
      return <Users className={className} aria-hidden />;
    case "split":
      return <Split className={className} aria-hidden />;
    case "server":
      return <Server className={className} aria-hidden />;
    case "zap":
      return <Zap className={className} aria-hidden />;
    case "database":
      return <Database className={className} aria-hidden />;
    default:
      return <Box className={className} aria-hidden />;
  }
}

/** Colored icon tile identifying a component kind. */
export function KindTile({
  kind,
  icon,
  size = "md",
  className,
}: {
  kind: Kind;
  icon: string | undefined;
  size?: "sm" | "md" | "lg";
  className?: string;
}) {
  const dims = { sm: "size-7 rounded-md [&_svg]:size-3.5", md: "size-9 rounded-lg [&_svg]:size-4", lg: "size-11 rounded-xl [&_svg]:size-5" }[size];
  return (
    <div
      className={cn("flex shrink-0 items-center justify-center", dims, className)}
      style={{
        background: kindSoft(kind, 16),
        color: kindColor(kind),
        boxShadow: `inset 0 0 0 1px ${kindSoft(kind, 35)}`,
      }}
    >
      <ComponentIcon name={icon} />
    </div>
  );
}
