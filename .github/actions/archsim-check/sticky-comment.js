// Posts or updates the archsim-check action's one comment for a design on a pull request, through
// the GitHub REST API client that actions/github-script gives (ADR-0064).
//
// The comment is the one whose body starts with the marker `<!-- archsim-check KEY -->` and that
// the job's own account posted (github-actions[bot] for the workflow's GITHUB_TOKEN; the
// comment-author input for a GitHub App or a personal token): updated when it exists, created
// otherwise. A comment anyone else posts with the marker is never taken over. The key (the
// design file, and the scenario) gives every design checked on one pull request its own comment.

/** What `archsim check --format markdown` starts with; replaced by the keyed marker. */
const MARKER = "<!-- archsim-check -->";

/** The login comments made with the workflow's GITHUB_TOKEN carry. */
const DEFAULT_AUTHOR = "github-actions[bot]";

/** A pull request with more than 5,000 comments gets a new comment rather than a long search. */
const MAX_PAGES = 50;

const PAGE_SIZE = 100;

/** The longest key a marker keeps, in characters. */
const MAX_KEY = 200;

const KEPT = /^[A-Za-z0-9._/#=+@-]$/;

/** The hidden marker of the comment for `key`: one line, with no `-->` or HTML inside. */
function marker(key) {
  if (!key) return MARKER;
  const safe = Array.from(key, (character) => (KEPT.test(character) ? character : "_"))
    .slice(0, MAX_KEY)
    .join("");
  return `<!-- archsim-check ${safe} -->`;
}

/** The comment's body: the check's Markdown under the design's own marker. */
function commentBody(markdown, key) {
  const text = markdown.startsWith(MARKER) ? markdown.slice(MARKER.length).replace(/^[\r\n]+/, "") : markdown;
  return `${marker(key)}\n${text}`;
}

/**
 * Updates the job's comment for `key` on the pull request, else creates one.
 * @returns {Promise<[string, number]>} what was done ("updated" or "created") and the comment's id
 */
async function upsert({ github, owner, repo, pr, markdown, key = "", author = DEFAULT_AUTHOR }) {
  const mark = marker(key);
  const body = commentBody(markdown, key);
  for (let page = 1; page <= MAX_PAGES; page++) {
    const { data: comments } = await github.rest.issues.listComments({
      owner,
      repo,
      issue_number: pr,
      per_page: PAGE_SIZE,
      page,
    });
    const own = comments.find((comment) => comment.user?.login === author && (comment.body ?? "").startsWith(mark));
    if (own) {
      await github.rest.issues.updateComment({ owner, repo, comment_id: own.id, body });
      return ["updated", own.id];
    }
    if (comments.length < PAGE_SIZE) break;
  }
  const { data: created } = await github.rest.issues.createComment({ owner, repo, issue_number: pr, body });
  return ["created", created.id];
}

module.exports = { DEFAULT_AUTHOR, MARKER, marker, upsert };
