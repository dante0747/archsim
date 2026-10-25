"use client";

/**
 * Technology icons: the product logo where one exists (Simple Icons, CC0, bundled at build time),
 * otherwise a plain icon for the component family. Logos identify a technology; they do not imply
 * that the simulation reproduces it exactly (behaviour always comes from the numbers).
 */
import {
  AppWindow,
  Archive,
  Braces,
  Cloud,
  CreditCard,
  Database,
  Globe,
  KeyRound,
  Layers,
  type LucideIcon,
  Mail,
  MessageSquare,
  Network,
  Router,
  Server,
  Smartphone,
  Sparkles,
  SquareFunction,
  Users,
  Zap,
} from "lucide-react";
import {
  type SimpleIcon,
  siAndroid,
  siAngular,
  siApachecassandra,
  siApachekafka,
  siApple,
  siCockroachlabs,
  siCouchbase,
  siDotnet,
  siElasticsearch,
  siEnvoyproxy,
  siFlutter,
  siGo,
  siGooglepubsub,
  siGraphql,
  siK3s,
  siKong,
  siKubernetes,
  siMariadb,
  siMongodb,
  siMysql,
  siNatsdotio,
  siNextdotjs,
  siNginx,
  siNodedotjs,
  siOpenapiinitiative,
  siOpenjdk,
  siOpensearch,
  siPhp,
  siPostgresql,
  siPython,
  siRabbitmq,
  siReact,
  siRedhatopenshift,
  siRedis,
  siRuby,
  siRust,
  siScylladb,
  siSvelte,
  siTraefikproxy,
  siVuedotjs,
} from "simple-icons";
import { useIsDark } from "@/components/ui/theme-toggle";
import { KIND_COLOR, type Kind } from "@/lib/theme";
import { cn } from "@/lib/utils";

const BRANDS: Record<string, SimpleIcon> = {
  postgresql: siPostgresql,
  mysql: siMysql,
  mariadb: siMariadb,
  cockroachdb: siCockroachlabs,
  mongodb: siMongodb,
  cassandra: siApachecassandra,
  scylladb: siScylladb,
  couchbase: siCouchbase,
  elasticsearch: siElasticsearch,
  opensearch: siOpensearch,
  redis: siRedis,
  redis_streams: siRedis,
  nginx: siNginx,
  envoy: siEnvoyproxy,
  traefik: siTraefikproxy,
  kong: siKong,
  kafka: siApachekafka,
  rabbitmq: siRabbitmq,
  nats: siNatsdotio,
  pubsub: siGooglepubsub,
  java: siOpenjdk,
  nodejs: siNodedotjs,
  go: siGo,
  python: siPython,
  dotnet: siDotnet,
  rust: siRust,
  php: siPhp,
  ruby: siRuby,
  // Kubernetes (managed offerings are Kubernetes too: they share its logo)
  kubernetes: siKubernetes,
  eks: siKubernetes,
  gke: siKubernetes,
  aks: siKubernetes,
  k3s: siK3s,
  openshift: siRedhatopenshift,
  // client groups
  ios: siApple,
  android: siAndroid,
  react_native: siReact,
  flutter: siFlutter,
  react: siReact,
  nextjs: siNextdotjs,
  vue: siVuedotjs,
  angular: siAngular,
  svelte: siSvelte,
  rest: siOpenapiinitiative,
  graphql: siGraphql,
};

/** Technologies without a logo (generic or "-style" managed services) get a plain icon. */
const GENERIC: Record<string, LucideIcon> = {
  cdn: Globe,
  valkey: Zap,
  memcached: Zap,
  varnish: Zap,
  aws_alb: Network,
  api_gateway: Router,
  sqlserver: Database,
  dynamodb: Database,
  sqs: Layers,
  payments: CreditCard,
  email: Mail,
  sms: MessageSquare,
  identity: KeyRound,
  llm: Sparkles,
  object_storage: Archive,
  serverless: SquareFunction,
  mobile: Smartphone,
  browser: AppWindow,
  api_client: Braces,
};

const BY_KIND: Record<Kind, LucideIcon> = {
  traffic: Users,
  network: Network,
  compute: Server,
  cache: Zap,
  database: Database,
  messaging: Layers,
  external: Cloud,
  other: Cloud,
};

function luminance(hex: string): number {
  const n = Number.parseInt(hex, 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

export function hasLogo(technology: string | null | undefined): boolean {
  return !!technology && technology in BRANDS;
}

/**
 * Logo or family icon. `tile` draws it on a small rounded square, as in the parts library and on
 * canvas components.
 */
export function TechIcon({
  technology,
  kind,
  size = 16,
  tile = false,
  className,
  title,
}: {
  technology?: string | null;
  kind: Kind;
  size?: number;
  tile?: boolean;
  className?: string;
  title?: string;
}) {
  const dark = useIsDark();
  const brand = technology ? BRANDS[technology] : undefined;
  const Generic = (technology && GENERIC[technology]) || BY_KIND[kind];
  let icon: React.ReactNode;
  if (brand) {
    // Very dark brand colours disappear on the dark theme: draw those in the text colour instead.
    const fill = dark && luminance(brand.hex) < 0.09 ? "var(--ink)" : `#${brand.hex}`;
    icon = (
      <svg viewBox="0 0 24 24" width={size} height={size} role="img" aria-label={title ?? brand.title} className="shrink-0">
        <path d={brand.path} fill={fill} />
      </svg>
    );
  } else {
    icon = <Generic className="shrink-0" style={{ width: size, height: size, color: KIND_COLOR[kind] }} strokeWidth={1.9} aria-label={title} aria-hidden={!title} />;
  }
  if (!tile) return <span className={cn("inline-flex", className)}>{icon}</span>;
  return (
    <span
      className={cn("inline-flex shrink-0 items-center justify-center rounded-lg border", className)}
      style={{
        width: size + 14,
        height: size + 14,
        background: `color-mix(in srgb, ${KIND_COLOR[kind]} 13%, var(--surface))`,
        borderColor: `color-mix(in srgb, ${KIND_COLOR[kind]} 35%, transparent)`,
      }}
    >
      {icon}
    </span>
  );
}
