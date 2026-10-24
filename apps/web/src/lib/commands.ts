/** Search ranking for the command palette. */

export interface Rankable {
  label: string;
  group: string;
  keywords?: string;
  hint?: string;
}

/**
 * Keep the items where every query word matches somewhere, best first: a word in the label scores
 * above one in the group/keywords, which scores above one in the description; a label that starts
 * with the whole query gets a bonus. Ties keep their original order. An empty query keeps all.
 */
export function rankCommands<T extends Rankable>(items: T[], query: string): T[] {
  const q = query.trim().toLowerCase();
  const words = q.split(/\s+/).filter(Boolean);
  if (!words.length) return items;
  const scored: { item: T; score: number }[] = [];
  for (const item of items) {
    const label = item.label.toLowerCase();
    const tags = `${item.group} ${item.keywords ?? ""}`.toLowerCase();
    const hint = (item.hint ?? "").toLowerCase();
    let score = 0;
    for (const w of words) {
      const s = label.includes(w) ? 4 : tags.includes(w) ? 2 : hint.includes(w) ? 1 : 0;
      if (!s) {
        score = 0;
        break;
      }
      score += s;
    }
    if (score) scored.push({ item, score: score + (label.startsWith(q) ? 2 : 0) });
  }
  return scored.sort((a, b) => b.score - a.score).map((x) => x.item);
}
