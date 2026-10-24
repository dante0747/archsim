import { EditorApp } from "@/components/editor/EditorApp";

export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  return <EditorApp projectId={id} />;
}
