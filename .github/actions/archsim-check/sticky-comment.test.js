// The archsim-check action's comment (sticky-comment.js): it updates its own comment for a design
// on a pull request, never another's. Run: node --test .github/actions/archsim-check/
const assert = require("node:assert/strict");
const { test } = require("node:test");

const { MARKER, marker, upsert } = require("./sticky-comment.js");

const BOT = { login: "github-actions[bot]", type: "Bot" };
const PULL_REQUEST = { owner: "o", repo: "r", pr: 3 };

/** A stand-in for github-script's client: answers each page from `pages` and records each call. */
function github(pages) {
  const calls = [];
  const record = (method, answer) => async (parameters) => {
    calls.push([method, parameters]);
    return { data: answer(parameters) };
  };
  return {
    calls,
    rest: {
      issues: {
        listComments: record("list", ({ page }) => pages[page - 1] ?? []),
        updateComment: record("update", () => ({})),
        createComment: record("create", () => ({ id: 9 })),
      },
    },
  };
}

test("updates its one comment, looking past a full page", async () => {
  const fullPage = Array.from({ length: 100 }, (_, id) => ({ id, body: "hello", user: BOT }));
  const client = github([fullPage, [{ id: 7, body: `${MARKER}\nold`, user: BOT }]]);

  const done = await upsert({ github: client, ...PULL_REQUEST, markdown: "new body" });

  assert.deepEqual(done, ["updated", 7]);
  assert.deepEqual(client.calls.at(-1), ["update", { owner: "o", repo: "r", comment_id: 7, body: `${MARKER}\nnew body` }]);
});

test("creates a comment when it has none, after the last page", async () => {
  const fullPage = Array.from({ length: 100 }, (_, id) => ({ id, body: "hello", user: BOT }));
  const client = github([fullPage, []]);

  const done = await upsert({ github: client, ...PULL_REQUEST, markdown: `${MARKER}\nbody` });

  assert.deepEqual(done, ["created", 9]);
  assert.deepEqual(client.calls.map(([method]) => method), ["list", "list", "create"]);
});

test("never takes over a comment someone else posted with its marker", async () => {
  // a pull request's author may post a comment carrying the marker: taking it over would hide the
  // real verdict in an old thread; so would a comment that merely quotes the marker
  const key = "archsim/design.json";
  const mark = marker(key);
  const comments = [
    { id: 1, body: `${mark}\nfake: passes`, user: { login: "mallory", type: "User" } },
    { id: 2, body: `${MARKER}\nfake: passes`, user: { login: "mallory", type: "User" } },
    { id: 3, body: `quoting it:\n${mark}\n`, user: BOT },
    { id: 4, body: `${mark}\nfrom another app`, user: { login: "some-app[bot]", type: "Bot" } },
    { id: 5, body: `${marker("other/design.json")}\nother`, user: BOT },
  ];
  const client = github([comments]);

  const done = await upsert({ github: client, ...PULL_REQUEST, markdown: `${MARKER}\n### result`, key });

  assert.deepEqual(done, ["created", 9]);
  assert.equal(client.calls.filter(([method]) => method === "update").length, 0);
});

test("updates its own comment for the design, under the design's marker", async () => {
  const key = "archsim/design.json";
  const mark = marker(key);
  const client = github([[{ id: 6, body: `${mark}\nold`, user: BOT }]]);

  const done = await upsert({ github: client, ...PULL_REQUEST, markdown: `${MARKER}\n### result`, key });

  assert.deepEqual(done, ["updated", 6]);
  assert.equal(client.calls.at(-1)[1].body, `${mark}\n### result`);
});

test("updates the comment of the account it is told it posts as", async () => {
  // a token of another account (a GitHub App, a personal token)
  const key = "archsim/design.json";
  const client = github([[{ id: 4, body: `${marker(key)}\nfrom another app`, user: { login: "some-app[bot]" } }]]);

  const done = await upsert({ github: client, ...PULL_REQUEST, markdown: "body", key, author: "some-app[bot]" });

  assert.deepEqual(done, ["updated", 4]);
});

test("gives each design and scenario a marker of its own", () => {
  const design = marker("archsim/design.json");
  const scenario = marker("archsim/design.json#peak");

  assert.equal(marker(""), MARKER);
  assert.notEqual(design, scenario);
  assert.ok(!scenario.startsWith(design) && !design.startsWith(MARKER));
});

test("keeps a marker one line, with no HTML or end of comment inside", () => {
  const evil = marker("x --> <img src=y> \n");

  assert.ok(evil.startsWith("<!-- ") && evil.endsWith(" -->"));
  assert.equal(evil.split("-->").length - 1, 1);
  assert.ok(!evil.includes("<img") && !evil.includes("\n"));
});

test("writes a character beyond the Basic Multilingual Plane as one underscore, as the Python script did", () => {
  assert.equal(marker("design-" + String.fromCodePoint(0x1f600) + ".json"), "<!-- archsim-check design-_.json -->");
});

test("keeps at most 200 characters of a long key", () => {
  assert.equal(marker("d".repeat(300)), `<!-- archsim-check ${"d".repeat(200)} -->`);
});
