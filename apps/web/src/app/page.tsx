import { connection } from "next/server";
import { ProjectsHome } from "@/components/projects/ProjectsHome";
import { privacyUrl } from "@/lib/privacy";

export default async function Home() {
  // the privacy notice is the server's setting when the page is asked for, not when it was built
  await connection();
  return <ProjectsHome privacyUrl={privacyUrl(process.env)} />;
}
