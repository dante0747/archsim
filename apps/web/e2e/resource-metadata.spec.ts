/**
 * The API's protected resource metadata (RFC 9728) at the public address it names. The end-to-end
 * stack runs without sign-in, where the API has no such document, so what is checked is the way
 * there: the web server passes the address on to the API, whose own 404 comes back, rather than
 * answering with a page of its own. The document itself is the black-box API tests' (TokensTests).
 */
import { expect, test } from "@playwright/test";

for (const path of ["/.well-known/oauth-protected-resource/api/v1", "/.well-known/oauth-protected-resource"]) {
  test(`${path} reaches the API`, async ({ request }) => {
    const response = await request.get(path);

    expect(response.status()).toBe(404);
    expect(response.headers()["content-type"]).toContain("application/json");
    expect(await response.json()).toEqual({ detail: "Not Found" });
  });
}

test("the rest of /.well-known stays the web app's", async ({ request }) => {
  const response = await request.get("/.well-known/security.txt");

  expect(response.status()).toBe(404);
  expect(response.headers()["content-type"]).not.toContain("application/json");
});
