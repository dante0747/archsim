/** ArchSim's public repository on GitHub, and links to what it holds on main. */
export const REPOSITORY_URL = "https://github.com/dante0747/archsim";

/**
 * A file or folder of the repository on GitHub, at main: `docs/validation.md` is a file (blob),
 * `benchmarks/results/` a folder (tree), unless `kind` says otherwise.
 */
export function repositoryUrl(path: string, kind: "file" | "folder" = path.endsWith("/") ? "folder" : "file"): string {
  return `${REPOSITORY_URL}/${kind === "folder" ? "tree" : "blob"}/main/${path.replace(/^\/+/, "")}`;
}
