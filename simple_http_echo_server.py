#!/usr/bin/env python3
import argparse
import socket
import datetime
import threading


def log(msg: str) -> None:
    ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{ts}] {msg}", flush=True)


class SocketBuffer:
    def __init__(self, conn: socket.socket, initial: bytes = b"") -> None:
        self.conn = conn
        self.buf = bytearray(initial)

    def read_until(self, delim: bytes) -> bytes:
        while True:
            idx = self.buf.find(delim)
            if idx != -1:
                end = idx + len(delim)
                data = bytes(self.buf[:end])
                del self.buf[:end]
                return data
            chunk = self.conn.recv(4096)
            if not chunk:
                data = bytes(self.buf)
                self.buf.clear()
                return data
            self.buf.extend(chunk)

    def read_exact(self, size: int) -> bytes:
        while len(self.buf) < size:
            chunk = self.conn.recv(4096)
            if not chunk:
                break
            self.buf.extend(chunk)
        data = bytes(self.buf[:size])
        del self.buf[:size]
        return data


def read_http_request(conn: socket.socket) -> bytes:
    header_data = b""
    while b"\r\n\r\n" not in header_data:
        chunk = conn.recv(4096)
        if not chunk:
            return b""
        header_data += chunk

    header_end = header_data.find(b"\r\n\r\n") + 4
    head = header_data[:header_end]
    rest = header_data[header_end:]

    reader = SocketBuffer(conn, rest)
    headers_text = head.decode("latin-1")
    lower_headers = headers_text.lower()

    body = b""
    if "content-length:" in lower_headers:
        content_length = 0
        for line in headers_text.split("\r\n"):
            if line.lower().startswith("content-length:"):
                content_length = int(line.split(":", 1)[1].strip())
                break
        body = reader.read_exact(content_length)
    elif "transfer-encoding:" in lower_headers and "chunked" in lower_headers:
        chunks = bytearray()
        while True:
            size_line = reader.read_until(b"\r\n")
            chunks.extend(size_line)
            chunk_size_hex = size_line.split(b";", 1)[0].strip()
            chunk_size = int(chunk_size_hex, 16)
            if chunk_size == 0:
                while True:
                    trailer_line = reader.read_until(b"\r\n")
                    chunks.extend(trailer_line)
                    if trailer_line == b"\r\n":
                        break
                break
            chunks.extend(reader.read_exact(chunk_size + 2))
        body = bytes(chunks)

    return head + body


def handle_connection(conn: socket.socket, addr) -> None:
    log(f"[CONN] Accepted connection from {addr[0]}:{addr[1]}")
    with conn:
        raw_request = read_http_request(conn)
        if not raw_request:
            log(f"[CONN] {addr[0]}:{addr[1]} connected but sent no data (EOF), closing.")
            return

        first_line = raw_request.split(b"\r\n", 1)[0].decode("latin-1")
        log(f"[RECV] {addr[0]}:{addr[1]} -> {first_line} ({len(raw_request)} bytes)")

        response = (
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Type: text/plain; charset=utf-8\r\n"
            b"Content-Length: 2\r\n"
            b"Connection: close\r\n"
            b"\r\n"
            b"OK"
        )
        conn.sendall(response)
        log(f"[SEND] {addr[0]}:{addr[1]} <- 200 OK (Connection: close)")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Simple HTTP echo server: print full request and reply 200."
    )
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8080, help="Bind port (default: 8080)")
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(1024)
        print(f"Listening on http://{args.host}:{args.port}", flush=True)

        try:
            while True:
                conn, addr = server.accept()
                threading.Thread(target=handle_connection, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt:
            log("Server stopped.")


if __name__ == "__main__":
    main()
