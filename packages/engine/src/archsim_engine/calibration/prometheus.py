"""Reading Prometheus: range queries over HTTP, or replayed from a recording (tests, demos and
offline use; no network). Nothing is sent anywhere except the queries to the server you name.

The HTTP client is bounded: one deadline covers connecting, the headers and the whole body (a
server that trickles bytes cannot hold a call past it), responses are capped in size, redirects
are not followed, and an optional host allowlist (``allowed_hosts``; the API reads
``ARCHSIM_PROMETHEUS_ALLOWED_HOSTS``) refuses any other server. Error messages never repeat what
the server said (``PrometheusError.detail`` keeps it for local tools that want to show it).

The host name is resolved once per query and the connection goes to that address (TLS is still
checked against the name), so a name cannot point at one address for a check and another for the
call. With ``require_global`` (a hosted API) every address the name resolves to must be public:
loopback, private, link-local (cloud metadata), CGNAT, unique-local IPv6 and other special ranges
are refused. An IP address written in the allowlist itself is trusted as written."""

from __future__ import annotations

import contextlib
import http.client
import ipaddress
import json
import math
import socket
import ssl
import threading
import time
import urllib.parse
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from archsim_engine.calibration.fit import Histogram
from archsim_engine.calibration.mapping import Range

Series = list[dict[str, Any]]  # Prometheus matrix: [{"metric": {...}, "values": [[ts, "v"], ...]}]

DEFAULT_TIMEOUT_S = 30.0
DEFAULT_MAX_BYTES = 50 * 1024 * 1024
"""Largest response accepted from a Prometheus server (50 MiB)."""
ERROR_TYPES = frozenset(
    {"bad_data", "execution", "timeout", "canceled", "internal", "unavailable", "not_found"}
)
"""Prometheus' own ``errorType`` values: the only part of an error response that is repeated."""


class PrometheusClient(Protocol):
    @property
    def source(self) -> str: ...

    def query_range(self, query: str, start: float, end: float, step: float) -> Series: ...


class PrometheusError(RuntimeError):
    """A query failed. ``str()`` is safe to show anyone; ``detail`` is what the server said (for
    the CLI, which runs for the person who named the server)."""

    def __init__(self, message: str, detail: str | None = None) -> None:
        super().__init__(message)
        self.detail = detail


def host_allowed(url: str, allowed: Sequence[str]) -> bool:
    """Whether ``url``'s host is in ``allowed`` (entries ``host`` or ``host:port``; IPv6 as
    ``[::1]:9090``). An empty list allows any host."""
    if not allowed:
        return True
    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").lower()
    port = parts.port or (443 if parts.scheme == "https" else 80)
    for entry in allowed:
        e = entry.strip().lower()
        name, sep, entry_port = e.rpartition(":")
        if not sep or not entry_port.isdigit() or (name.count(":") and not name.endswith("]")):
            name, entry_port = e, ""  # no port given (or a bare IPv6 address)
        if name.strip("[]") == host and (not entry_port or int(entry_port) == port):
            return True
    return False


IpAddress = ipaddress.IPv4Address | ipaddress.IPv6Address

NAT64 = ipaddress.ip_network("64:ff9b::/96")


def is_public(address: IpAddress) -> bool:
    """Whether an address is on the public internet: not loopback, private, link-local, CGNAT,
    unique-local, multicast, reserved or unspecified (an IPv4 address inside IPv6, mapped or
    NAT64, is judged as the IPv4 address it carries)."""
    if isinstance(address, ipaddress.IPv6Address):
        inner = address.ipv4_mapped
        if inner is None and address in NAT64:
            inner = ipaddress.IPv4Address(int(address) & 0xFFFFFFFF)
        if inner is not None:
            return is_public(inner)
    return (
        address.is_global
        and not address.is_multicast
        and not address.is_reserved
        and not address.is_unspecified
    )


def _ip_literal(host: str) -> IpAddress | None:
    try:
        return ipaddress.ip_address(host.strip("[]"))
    except ValueError:
        return None


class _PinnedHTTP(http.client.HTTPConnection):
    """Connects to an address resolved beforehand; the Host header keeps the name."""

    def __init__(self, host: str, port: int | None, address: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address

    def connect(self) -> None:
        self.sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)


class _PinnedHTTPS(http.client.HTTPSConnection):
    """Like ``_PinnedHTTP``, with TLS verified against the host name (not the address)."""

    def __init__(
        self,
        host: str,
        port: int | None,
        address: str,
        timeout: float,
        context: ssl.SSLContext | None,
    ) -> None:
        self._tls = context or ssl.create_default_context()
        super().__init__(host, port, timeout=timeout, context=self._tls)
        self._address = address

    def connect(self) -> None:
        sock = socket.create_connection((self._address, self.port), self.timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            self.sock = self._tls.wrap_socket(sock, server_hostname=self.host)
        except BaseException:
            sock.close()
            raise


@dataclass
class HttpPrometheus:
    """``GET <url>/api/v1/query_range``. Only called when you run a calibration."""

    url: str
    timeout_s: float = DEFAULT_TIMEOUT_S
    """One deadline per query, covering connecting, the headers and the whole body."""
    max_bytes: int = DEFAULT_MAX_BYTES
    allowed_hosts: Sequence[str] = ()
    """Hosts this client may query (``host`` or ``host:port``); empty: any."""
    require_global: bool = False
    """Refuse a host name that resolves to anything but public addresses (see the module)."""
    ssl_context: ssl.SSLContext | None = None
    """Certificates to trust for https (default: the system's)."""

    @property
    def source(self) -> str:
        return f"Prometheus {self.url}"

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        parts = urllib.parse.urlsplit(self.url)
        try:
            parts.port  # noqa: B018 - raises ValueError for a malformed port
        except ValueError:
            raise PrometheusError("the Prometheus URL has an invalid port") from None
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise PrometheusError("the Prometheus URL must be http(s)://host[:port]")
        if not host_allowed(self.url, self.allowed_hosts):
            raise PrometheusError(
                f"{parts.hostname} is not an allowed Prometheus host "
                "(ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)"
            )
        params = urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": step})
        status, raw = self._get(parts, f"{parts.path.rstrip('/')}/api/v1/query_range?{params}")
        try:
            body = json.loads(raw)
        except ValueError:
            body = None
        if not isinstance(body, dict):
            raise PrometheusError(
                f"{self.url} did not answer like a Prometheus server (HTTP {status})"
            )
        if status != 200 and body.get("status") == "success":
            raise PrometheusError(f"{self.url} answered HTTP {status}: {query}")
        return _matrix(body, query)

    def _address(self, host: str, port: int) -> str:
        """The one address this query connects to, checked when ``require_global``."""
        try:
            infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except (OSError, UnicodeError):
            raise PrometheusError(f"cannot query {self.url}: unknown host") from None
        addresses = [str(info[4][0]).split("%")[0] for info in infos]
        if not addresses:
            raise PrometheusError(f"cannot query {self.url}: unknown host")
        literal = _ip_literal(host)
        trusted = literal is not None and bool(self.allowed_hosts)  # an IP the allowlist names
        if self.require_global and not trusted:
            for a in addresses:
                ip = _ip_literal(a)
                if ip is None or not is_public(ip):
                    raise PrometheusError(
                        f"{host} is a loopback, private, link-local or otherwise non-public "
                        "address, which this server does not call"
                    )
        return addresses[0]

    def _get(self, parts: urllib.parse.SplitResult, path: str) -> tuple[int, bytes]:
        """One GET under one deadline. A watchdog shuts the socket down when the deadline
        passes, so neither slow headers nor a trickled body can hold the call past it."""
        deadline = time.monotonic() + self.timeout_s
        host = parts.hostname or ""
        port = parts.port or (443 if parts.scheme == "https" else 80)
        address = self._address(host, port)
        conn: http.client.HTTPConnection
        if parts.scheme == "https":
            conn = _PinnedHTTPS(host, port, address, self.timeout_s, self.ssl_context)
        else:
            conn = _PinnedHTTP(host, port, address, self.timeout_s)
        expired = threading.Event()
        held: list[socket.socket] = []

        def abort() -> None:
            expired.set()
            for sock in held:
                with contextlib.suppress(OSError):
                    sock.shutdown(socket.SHUT_RDWR)

        timer = threading.Timer(self.timeout_s, abort)
        timer.daemon = True
        timer.start()
        try:
            conn.connect()
            if conn.sock is not None:
                held.append(conn.sock)
            if expired.is_set():
                raise TimeoutError
            conn.request("GET", path, headers={"Accept": "application/json"})
            resp = conn.getresponse()
            length = resp.getheader("Content-Length") or ""
            if length.isdigit() and int(length) > self.max_bytes:
                raise self._too_large()
            body = bytearray()
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or expired.is_set():
                    raise TimeoutError
                for sock in held:
                    # closed once a server that ends the connection has sent the whole body
                    with contextlib.suppress(OSError):
                        sock.settimeout(remaining)
                chunk = resp.read1(64 * 1024)
                if not chunk:
                    break
                body += chunk
                if len(body) > self.max_bytes:
                    raise self._too_large()
            if expired.is_set():  # the watchdog ended the body: it is not all there
                raise TimeoutError
            if resp.length:  # the server closed before sending what it announced
                raise http.client.IncompleteRead(bytes(body), resp.length)
            return resp.status, bytes(body)
        except PrometheusError:
            raise
        except (OSError, http.client.HTTPException, ValueError) as exc:
            if expired.is_set() or time.monotonic() >= deadline or isinstance(exc, TimeoutError):
                raise PrometheusError(
                    f"{self.url} did not answer within {self.timeout_s:g} s"
                ) from None
            raise PrometheusError(f"cannot query {self.url}: {_reason(exc)}") from None
        finally:
            timer.cancel()
            conn.close()

    def _too_large(self) -> PrometheusError:
        return PrometheusError(
            f"the response from {self.url} is larger than {self.max_bytes:,} bytes: "
            "use a larger step or a shorter range"
        )


def _reason(exc: BaseException) -> str:
    """Why a request failed, in words that carry nothing the server sent."""
    if isinstance(exc, http.client.IncompleteRead):
        return "the response was cut off"
    if isinstance(exc, http.client.RemoteDisconnected):
        return "the server closed the connection"
    if isinstance(exc, http.client.HTTPException):
        return f"not a valid HTTP response ({type(exc).__name__})"
    if isinstance(exc, ConnectionRefusedError):
        return "connection refused"
    if isinstance(exc, socket.gaierror):
        return "unknown host"
    if isinstance(exc, ssl.SSLCertVerificationError):
        return "its TLS certificate is not valid for this host"
    if isinstance(exc, OSError) and exc.strerror:
        return str(exc.strerror)
    return type(exc).__name__


@dataclass
class RecordedPrometheus:
    """Responses recorded earlier: ``{"source": ..., "range": {start, end, step},
    "responses": {query: response}}``. ``range`` is the window the queries covered; when a
    recording has one, calibration uses it (the data came from there)."""

    responses: dict[str, Any]
    label: str = "recorded Prometheus responses"
    recorded_range: Range | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any], name: str = "") -> RecordedPrometheus:
        window = data.get("range")
        responses = data.get("responses") or {}
        if not isinstance(responses, dict):
            raise PrometheusError("a recording's 'responses' must map each query to its response")
        return cls(
            responses,
            str(data.get("source") or f"recorded responses {name}".strip())[:200],
            Range.model_validate(window) if window else None,
        )

    @classmethod
    def load(cls, path: str | Path) -> RecordedPrometheus:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_dict(data, Path(path).name)

    @property
    def source(self) -> str:
        return self.label

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        if query not in self.responses:
            raise PrometheusError(f"no recorded response for query: {query}")
        return _matrix(self.responses[query], query)


@dataclass
class RecordingPrometheus:
    """Wraps a client and keeps every response, to save as a recording (``save``)."""

    inner: PrometheusClient
    responses: dict[str, Any] = field(default_factory=dict)
    window: tuple[float, float, float] | None = None

    @property
    def source(self) -> str:
        return self.inner.source

    def query_range(self, query: str, start: float, end: float, step: float) -> Series:
        result = self.inner.query_range(query, start, end, step)
        self.window = (start, end, step)
        self.responses[query] = {
            "status": "success",
            "data": {"resultType": "matrix", "result": result},
        }
        return result

    def save(self, path: str | Path) -> None:
        out: dict[str, Any] = {"source": self.source}
        if self.window:
            start, end, step = self.window
            out["range"] = {"start": _iso(start), "end": _iso(end), "step": step}
        out["responses"] = self.responses
        Path(path).write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, UTC).isoformat().replace("+00:00", "Z")


def _matrix(body: Any, query: str) -> Series:
    if not isinstance(body, dict):
        raise PrometheusError(f"not a Prometheus API response for: {query}")
    if body.get("status") != "success":
        kind = body.get("errorType")
        said = body.get("error")
        raise PrometheusError(
            f"query failed ({kind if isinstance(kind, str) and kind in ERROR_TYPES else 'error'})"
            f": {query}",
            str(said)[:2000] if said is not None else None,
        )
    data = body.get("data")
    if not isinstance(data, dict) or data.get("resultType") != "matrix":
        raise PrometheusError(f"expected a range (matrix) result for: {query}")
    result = data.get("result") or []
    if not isinstance(result, list) or not all(
        isinstance(s, dict)
        and isinstance(s.get("metric") or {}, dict)
        and isinstance(s.get("values") or [], list)
        for s in result
    ):
        raise PrometheusError(f"a malformed matrix result for: {query}")
    return list(result)


def _values(series: dict[str, Any]) -> list[tuple[float, float]]:
    out = []
    try:
        for ts, v in series.get("values") or []:
            x = float(v)
            if math.isfinite(x):
                out.append((float(ts), x))
    except (TypeError, ValueError):
        raise PrometheusError("a series has malformed [timestamp, value] points") from None
    return out


def time_mean(series: dict[str, Any]) -> float | None:
    vals = [v for _, v in _values(series)]
    return sum(vals) / len(vals) if vals else None


def summed(result: Series) -> list[tuple[float, float]]:
    """The series added up per timestamp (one series stays as it is)."""
    acc: dict[float, float] = {}
    for s in result:
        for ts, v in _values(s):
            acc[ts] = acc.get(ts, 0.0) + v
    return sorted(acc.items())


def histogram(result: Series, unit: str) -> Histogram:
    """A histogram from ``*_bucket`` series (one per ``le``), averaged over the range."""
    buckets: dict[float, float] = {}
    for s in result:
        le = (s.get("metric") or {}).get("le")
        if le is None:
            raise PrometheusError(
                "a histogram query must return series with an 'le' label (use sum by (le))"
            )
        m = time_mean(s)
        if m is not None:
            try:
                bound = math.inf if le in ("+Inf", "Inf", "inf") else float(le)
            except (TypeError, ValueError):
                raise PrometheusError("a histogram series has a malformed 'le' label") from None
            if math.isnan(bound):
                raise PrometheusError("a histogram series has a malformed 'le' label")
            buckets[bound] = buckets.get(bound, 0.0) + m
    if not buckets:
        raise PrometheusError("the histogram query returned no data in the range")
    return Histogram.from_buckets(buckets, 1000.0 if unit == "s" else 1.0)


def reduce(result: Series, how: str) -> float:
    pts = [v for _, v in summed(result)]
    if not pts:
        raise PrometheusError("the query returned no data in the range")
    if how == "max":
        return max(pts)
    if how == "min":
        return min(pts)
    if how == "last":
        return pts[-1]
    return sum(pts) / len(pts)
