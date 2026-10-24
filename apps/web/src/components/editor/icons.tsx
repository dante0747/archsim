import { Box, Database, Server, Split, Users, Zap } from "lucide-react";

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
