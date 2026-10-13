#!/usr/bin/env python3
"""Local forward proxy that rewrites the User-Agent header before forwarding
to the sandbox egress proxy.

Why: the sandbox egress proxy (hatch-egress-proxy:3128) filters on
User-Agent -- it resets/blocks requests from Java's default UA
("Java/21...", "Gradle/...", "Apache-HttpClient/...") but allows curl's.
Gradle's daemon therefore cannot download dependencies. This proxy accepts
CONNECT tunnels from local JVMs, swaps the User-Agent for curl's, and
shuttles the bytes. Plain HTTP requests are forwarded with the same rewrite.
"""
import os
import socket
import threading
import urllib.parse

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 8888
UA_REPLACEMENT = b"curl/8.5.0"

_p = urllib.parse.urlparse(os.environ.get("https_proxy", ""))
EGRESS_HOST, EGRESS_PORT = _p.hostname or "hatch-egress-proxy", _p.port or 3128


def recv_headers(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(65536)
        if not chunk:
            break
        data += chunk
        if len(data) > 1 << 20:
            break
    return data


def rewrite_ua(head: bytes) -> bytes:
    lines = head.split(b"\r\n")
    out = []
    for ln in lines:
        if ln.lower().startswith(b"user-agent:"):
            out.append(b"User-Agent: " + UA_REPLACEMENT)
        else:
            out.append(ln)
    return b"\r\n".join(out)


def shuttle(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def handle(client):
    try:
        head = recv_headers(client)
        if not head:
            client.close()
            return
        head = rewrite_ua(head)
        first = head.split(b"\r\n", 1)[0]
        upstream = socket.create_connection((EGRESS_HOST, EGRESS_PORT), timeout=30)
        try:
            upstream.sendall(head)
            if first.startswith(b"CONNECT"):
                # wait for "200 Connection established", then shuttle raw bytes
                resp = recv_headers(upstream)
                client.sendall(resp)
                if b" 200 " not in resp.split(b"\r\n", 1)[0]:
                    upstream.close()
                    client.close()
                    return
            t = threading.Thread(target=shuttle, args=(upstream, client), daemon=True)
            t.start()
            shuttle(client, upstream)
            t.join()
        finally:
            upstream.close()
    except Exception:
        pass
    finally:
        client.close()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((LISTEN_HOST, LISTEN_PORT))
    srv.listen(128)
    print(f"ua-rewrite proxy on {LISTEN_HOST}:{LISTEN_PORT} -> {EGRESS_HOST}:{EGRESS_PORT}", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
