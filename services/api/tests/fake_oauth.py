"""A stand-in GitHub and Google for sign-in tests: the authorize step, the token endpoint (which
checks the PKCE verifier against the challenge, like the real ones) and the profile endpoints.

In tests, ``FakeProviders.handle`` is an ``httpx2.MockTransport`` handler for the API's calls
and ``authorize`` plays the browser's visit to the provider. Run as a script, it serves the same
over HTTP for trying sign-in in a browser without a real OAuth app::

    python services/api/tests/fake_oauth.py --port 8018 --login ada --email ada@example.com

then start the API with ``ARCHSIM_GITHUB_URL=http://127.0.0.1:8018`` and
``ARCHSIM_GITHUB_API_URL=http://127.0.0.1:8018`` (any client id and secret).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlencode, urlsplit

import httpx2

GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
GOOGLE_USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"


@dataclass
class Account:
    id: int
    login: str
    name: str
    email: str | None
    verified: bool = True


@dataclass
class FakeProviders:
    client_id: str = "test-client"
    client_secret: str = "test-secret"
    accounts: dict[str, Account] = field(default_factory=dict)
    signing_in: str = "ada"
    """The account the next ``authorize`` signs in as."""
    codes: dict[str, dict[str, str]] = field(default_factory=dict)
    tokens: dict[str, str] = field(default_factory=dict)
    token_requests: list[dict[str, str]] = field(default_factory=list)
    refuse_token: bool = False

    def add(self, login: str, email: str | None, verified: bool = True, name: str = "") -> Account:
        account = Account(len(self.accounts) + 1000, login, name or login.title(), email, verified)
        self.accounts[login] = account
        return account

    # -- the browser's visit -------------------------------------------------------------------
    def authorize(self, url: str) -> str:
        """The provider's sign-in page approves at once: the URL to send the browser back to."""
        q = {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}
        assert q["client_id"] == self.client_id, "wrong client id"
        assert q["response_type"] == "code"
        assert q["code_challenge_method"] == "S256", "PKCE is required"
        code = secrets.token_urlsafe(16)
        self.codes[code] = {
            "challenge": q["code_challenge"],
            "redirect_uri": q["redirect_uri"],
            "login": self.signing_in,
        }
        return f"{q['redirect_uri']}?{urlencode({'code': code, 'state': q['state']})}"

    # -- the API's calls -----------------------------------------------------------------------
    def handle(self, request: httpx2.Request) -> httpx2.Response:
        status, body = self.answer(
            request.method,
            str(request.url),
            request.headers.get("authorization", ""),
            request.content.decode() if request.method == "POST" else "",
        )
        return httpx2.Response(status, json=body)

    def answer(self, method: str, url: str, auth: str, form_text: str) -> tuple[int, Any]:
        path = urlsplit(url).path
        if method == "POST" and (path.endswith("/login/oauth/access_token") or url == GOOGLE_TOKEN):
            return self._token({k: v[0] for k, v in parse_qs(form_text).items()})
        account = self.accounts.get(self.tokens.get(auth.removeprefix("Bearer "), ""))
        if account is None:
            return 401, {"message": "Bad credentials"}
        if url.startswith(GOOGLE_USERINFO):
            return 200, {
                "sub": f"g-{account.id}",
                "email": account.email,
                "email_verified": account.verified,
                "name": account.name,
                "picture": "https://example.com/a.png",
            }
        if path.endswith("/user/emails"):
            emails = [{"email": account.email, "primary": True, "verified": account.verified}]
            return 200, emails if account.email else []
        if path.endswith("/user"):
            return 200, {
                "id": account.id,
                "login": account.login,
                "name": account.name,
                "email": None,  # GitHub hides it unless public: the API asks /user/emails
                "avatar_url": f"https://avatars.example.com/{account.id}",
            }
        return 404, {"message": "Not Found"}

    def _token(self, form: dict[str, str]) -> tuple[int, Any]:
        self.token_requests.append(form)
        if self.refuse_token:
            return 400, {"error": "bad_verification_code"}
        if (form.get("client_id"), form.get("client_secret")) != (
            self.client_id,
            self.client_secret,
        ):
            return 401, {"error": "incorrect_client_credentials"}
        grant = self.codes.pop(form.get("code", ""), None)
        if grant is None or form.get("redirect_uri") != grant["redirect_uri"]:
            return 400, {"error": "bad_verification_code"}
        digest = hashlib.sha256(form.get("code_verifier", "").encode()).digest()
        if base64.urlsafe_b64encode(digest).rstrip(b"=").decode() != grant["challenge"]:
            return 400, {"error": "invalid_grant", "error_description": "PKCE verification failed"}
        token = secrets.token_urlsafe(16)
        self.tokens[token] = grant["login"]
        return 200, {"access_token": token, "token_type": "bearer", "scope": "read:user"}


def serve(fake: FakeProviders, port: int) -> None:  # pragma: no cover - manual use
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, body: Any, location: str | None = None) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            if location:
                self.send_header("Location", location)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            url = f"http://127.0.0.1:{port}{self.path}"
            if self.path.startswith("/login/oauth/authorize"):
                self._send(302, {}, fake.authorize(url))
                return
            if self.path.startswith("/switch"):  # /switch?login=bob&email=bob@example.com
                q = {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}
                if q["login"] not in fake.accounts:
                    fake.add(q["login"], q.get("email") or f"{q['login']}@example.com")
                fake.signing_in = q["login"]
                self._send(200, {"signingInAs": fake.signing_in})
                return
            self._send(*fake.answer("GET", url, self.headers.get("Authorization", ""), ""))

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            form = self.rfile.read(length).decode()
            url = f"http://127.0.0.1:{port}{self.path}"
            self._send(*fake.answer("POST", url, self.headers.get("Authorization", ""), form))

    print(f"fake GitHub on http://127.0.0.1:{port} signs everyone in as {fake.signing_in}")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":  # pragma: no cover - manual use
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8018)
    parser.add_argument("--login", default="ada")
    parser.add_argument("--email", default="ada@example.com")
    parser.add_argument("--client-id", default="test-client")
    parser.add_argument("--client-secret", default="test-secret")
    args = parser.parse_args()
    fake = FakeProviders(args.client_id, args.client_secret, signing_in=args.login)
    fake.add(args.login, args.email)
    serve(fake, args.port)
