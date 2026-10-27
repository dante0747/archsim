"""Calling Prometheus from a hosted server: only public addresses, one resolution per query, the
connection pinned to the address that was checked, TLS still verified against the host name, and
no redirects."""

from __future__ import annotations

import contextlib
import datetime as dt
import http.server
import ipaddress
import json
import socket
import ssl
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pytest

from archsim_engine.calibration import HttpPrometheus, PrometheusError
from archsim_engine.calibration.prometheus import is_public

START = 1_700_000_000.0
SUCCESS = json.dumps({"status": "success", "data": {"resultType": "matrix", "result": []}}).encode()

REAL_GETADDRINFO = socket.getaddrinfo


@pytest.mark.parametrize(
    ("address", "public"),
    [
        ("8.8.8.8", True),
        ("2606:4700:4700::1111", True),
        ("127.0.0.1", False),
        ("10.1.2.3", False),
        ("172.16.0.9", False),
        ("192.168.1.1", False),
        ("169.254.169.254", False),  # cloud metadata
        ("100.64.0.1", False),  # CGNAT
        ("0.0.0.0", False),
        ("224.0.0.1", False),
        ("255.255.255.255", False),
        ("::1", False),
        ("fe80::1", False),
        ("fd00::1", False),  # unique local
        ("::ffff:127.0.0.1", False),  # IPv4 inside IPv6
        ("::ffff:8.8.8.8", True),
        ("64:ff9b::a00:1", False),  # NAT64 of 10.0.0.1
        ("::", False),
    ],
)
def test_only_public_addresses_count_as_public(address: str, public: bool) -> None:
    assert is_public(ipaddress.ip_address(address)) is public


@contextmanager
def resolving(names: dict[str, list[str]]) -> Iterator[list[str]]:
    """``socket.getaddrinfo`` answers ``names`` from the table (everything else as usual);
    yields the names looked up, in order."""
    looked_up: list[str] = []

    def fake(host: Any, port: Any, *args: Any, **kwargs: Any) -> Any:
        if host in names:
            looked_up.append(host)
            out = []
            for a in names[host]:
                family = socket.AF_INET6 if ":" in a else socket.AF_INET
                sockaddr = (a, port, 0, 0) if family == socket.AF_INET6 else (a, port)
                out.append((family, socket.SOCK_STREAM, 6, "", sockaddr))
            return out
        return REAL_GETADDRINFO(host, port, *args, **kwargs)

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(socket, "getaddrinfo", fake)
        yield looked_up


@contextmanager
def serve(
    handler: Callable[[http.server.BaseHTTPRequestHandler], None],
    tls: ssl.SSLContext | None = None,
) -> Iterator[int]:
    """A local server on 127.0.0.1 answering GETs with ``handler``; yields its port."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            with contextlib.suppress(OSError):
                handler(self)

        def log_message(self, *args: Any) -> None:
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    if tls is not None:
        server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()


def _answer(seen: list[str]) -> Callable[[Any], None]:
    def handle(h: Any) -> None:
        seen.append(h.headers.get("Host", ""))
        h.send_response(200)
        h.send_header("Content-Type", "application/json")
        h.send_header("Content-Length", str(len(SUCCESS)))
        h.end_headers()
        h.wfile.write(SUCCESS)

    return handle


def _query(url: str, **kw: Any) -> Any:
    return HttpPrometheus(url, timeout_s=5, **kw).query_range("up", START, START + 60, 15)


@pytest.mark.parametrize(
    "addresses",
    [
        ["127.0.0.1"],
        ["10.0.0.8"],
        ["192.168.0.4"],
        ["169.254.169.254"],
        ["100.64.12.1"],
        ["fd12::1"],
        ["fe80::1"],
        ["::ffff:127.0.0.1"],
        ["8.8.8.8", "10.0.0.8"],  # one private answer is enough to refuse
    ],
)
def test_a_name_resolving_to_a_non_public_address_is_refused(addresses: list[str]) -> None:
    with resolving({"prom.example.test": addresses}), pytest.raises(PrometheusError) as err:
        _query(
            "http://prom.example.test:9090",
            allowed_hosts=("prom.example.test",),
            require_global=True,
        )
    assert "non-public" in str(err.value)


def test_loopback_and_private_literals_are_refused_unless_the_allowlist_names_them() -> None:
    for url in ("http://127.0.0.1:9", "http://[::1]:9", "http://10.0.0.1:9"):
        with pytest.raises(PrometheusError, match="non-public"):
            _query(url, require_global=True)
    seen: list[str] = []
    with serve(_answer(seen)) as port:
        _query(
            f"http://127.0.0.1:{port}", allowed_hosts=(f"127.0.0.1:{port}",), require_global=True
        )
    assert seen == [f"127.0.0.1:{port}"]


def test_the_name_is_resolved_once_and_the_connection_goes_to_that_address() -> None:
    seen: list[str] = []
    with serve(_answer(seen)) as port, resolving({"prom.example.test": ["127.0.0.1"]}) as looked:
        _query(f"http://prom.example.test:{port}")
    assert looked == ["prom.example.test"]
    assert seen == [f"prom.example.test:{port}"]  # the Host header keeps the name


def test_redirects_are_still_not_followed() -> None:
    def redirect(h: Any) -> None:
        h.send_response(302)
        h.send_header("Location", "http://169.254.169.254/latest/meta-data/")
        h.send_header("Content-Length", "0")
        h.end_headers()

    with serve(redirect) as port, pytest.raises(PrometheusError, match="HTTP 302"):
        _query(f"http://127.0.0.1:{port}")


# -- TLS: verified against the host name, while connecting to the pinned address ---------------
def _tls_files(tmp: Path, names: list[str]) -> tuple[Path, Path, Path]:
    """A CA and a server certificate for ``names`` signed by it: (ca.pem, cert.pem, key.pem)."""
    x509 = pytest.importorskip("cryptography.x509")
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    now = dt.datetime.now(dt.UTC)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "ArchSim test CA")])
    ca = (
        x509.CertificateBuilder()
        .subject_name(ca_name)
        .issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(hours=1))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=True,
                crl_sign=True,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False
        )
        .sign(ca_key, hashes.SHA256())
    )
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, names[0])]))
        .issuer_name(ca_name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(hours=1))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName(n) for n in names]), False)
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), False
        )
        .sign(ca_key, hashes.SHA256())
    )
    pem = serialization.Encoding.PEM
    paths = (tmp / "ca.pem", tmp / "cert.pem", tmp / "key.pem")
    paths[0].write_bytes(ca.public_bytes(pem))
    paths[1].write_bytes(cert.public_bytes(pem))
    paths[2].write_bytes(
        key.private_bytes(pem, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    )
    return paths


@pytest.mark.parametrize(("cert_for", "ok"), [("prom.example.test", True), ("other.test", False)])
def test_tls_is_checked_against_the_host_name_not_the_address(
    tmp_path: Path, cert_for: str, ok: bool
) -> None:
    ca, cert, key = _tls_files(tmp_path, [cert_for])
    server_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_tls.load_cert_chain(cert, key)
    client_tls = ssl.create_default_context(cafile=str(ca))
    seen: list[str] = []
    with (
        serve(_answer(seen), server_tls) as port,
        resolving({"prom.example.test": ["127.0.0.1"]}),
    ):
        url = f"https://prom.example.test:{port}"
        if ok:
            _query(url, ssl_context=client_tls)
            assert seen == [f"prom.example.test:{port}"]
        else:
            with pytest.raises(PrometheusError, match="certificate"):
                _query(url, ssl_context=client_tls)
            assert seen == []
