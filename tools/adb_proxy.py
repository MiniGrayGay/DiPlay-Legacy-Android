"""A stand-in for the USB bulk endpoints, for verifying Android mode on emulators.

The head unit's ADB client cannot talk to an emulator's adbd over a TCP forward: adbd closes its
listener as soon as the host adb server takes the transport. So this process accepts the ADB *device*
protocol on a local port and runs each `exec:` / `shell:` service through `adb exec-out` on the real
guest. Everything above the transport is then genuine — the handshake, the stream multiplexing, the
phone's own `screenrecord`, and the H.264 it produces.

    python adb_proxy.py <adb-serial> [port]
"""

import socket
import struct
import subprocess
import sys
import threading

CNXN = 0x4E584E43
AUTH = 0x48545541
OPEN = 0x4E45504F
OKAY = 0x59414B4F
CLSE = 0x45534C43
WRTE = 0x45545257

MAX_PAYLOAD = 256 * 1024
CHUNK = 32 * 1024


def pack(command, arg0, arg1, payload=b""):
    return struct.pack("<6I", command, arg0, arg1, len(payload),
                       sum(payload) & 0xFFFFFFFF, command ^ 0xFFFFFFFF) + payload


def read_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def read_packet(sock):
    header = read_exact(sock, 24)
    command, arg0, arg1, length, _, magic = struct.unpack("<6I", header)
    if magic != command ^ 0xFFFFFFFF:
        raise ValueError("bad magic")
    payload = read_exact(sock, length) if length else b""
    return command, arg0, arg1, payload


class Proxy:
    def __init__(self, serial, port):
        self.serial = serial
        self.port = port
        self.conn = None
        self.write_lock = threading.Lock()
        self.next_remote = 100
        self.streams = {}
        self.processes = {}

    def send(self, command, arg0, arg1, payload=b""):
        with self.write_lock:
            self.conn.sendall(pack(command, arg0, arg1, payload))

    def serve(self):
        server = socket.socket()
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", self.port))
        server.listen(1)
        print("proxy listening on %d for %s" % (self.port, self.serial), flush=True)
        self.conn, _ = server.accept()
        print("head unit connected", flush=True)
        try:
            while True:
                command, arg0, arg1, payload = read_packet(self.conn)
                if command == CNXN:
                    self.send(CNXN, 0x01000000, MAX_PAYLOAD,
                              b"device::ro.product.model=proxy " + self.serial.encode() + b"\x00")
                elif command == AUTH:
                    self.send(CNXN, 0x01000000, MAX_PAYLOAD, b"device::\x00")
                elif command == OPEN:
                    self.open_stream(arg0, payload.rstrip(b"\x00").decode())
                elif command == CLSE:
                    self.close_stream(arg0)
        except (EOFError, ValueError, OSError) as error:
            print("client gone: %r" % (error,), flush=True)
        finally:
            for process in self.processes.values():
                process.kill()
            self.conn.close()

    def open_stream(self, local_id, service):
        remote = self.next_remote
        self.next_remote += 1
        self.streams[remote] = local_id
        if service.startswith("exec:"):
            command = ["adb", "-s", self.serial, "exec-out"] + service[len("exec:"):].split()
        elif service.startswith("shell:"):
            command = ["adb", "-s", self.serial, "shell"] + service[len("shell:"):].split()
        else:
            print("refusing service %r" % service, flush=True)
            self.send(CLSE, 0, local_id)
            return
        print("open %d <- %s" % (remote, " ".join(command[3:])), flush=True)
        self.send(OKAY, remote, local_id)
        threading.Thread(target=self.run_stream, args=(remote, local_id, command), daemon=True).start()

    def run_stream(self, remote, local_id, command):
        try:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            self.processes[remote] = process
            while True:
                # read1 returns as soon as anything is available, so frames are not held back until
                # a whole CHUNK has accumulated — which is what keeps the picture live.
                chunk = process.stdout.read1(CHUNK)
                if not chunk:
                    break
                self.send(WRTE, remote, local_id, chunk)
        except Exception as error:  # noqa: BLE001 - the proxy's only job is to keep the bytes flowing
            print("stream %d failed: %r" % (remote, error), flush=True)
        finally:
            self.processes.pop(remote, None)
            self.streams.pop(remote, None)
            # CLSE after the last WRTE, so the receiver sees a clean end of stream.
            try:
                self.send(CLSE, remote, local_id)
            except OSError:
                pass
            print("closed %d" % remote, flush=True)

    def close_stream(self, local_id):
        for remote, owner in list(self.streams.items()):
            if owner == local_id:
                process = self.processes.pop(remote, None)
                if process:
                    process.kill()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    Proxy(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 16000).serve()
