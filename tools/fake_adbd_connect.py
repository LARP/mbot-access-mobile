"""adbd FALSO (TLS, Android 11+) para comprobar contra el `adb` REAL el orden del protocolo que sigue AdbConnection.kt:
CNXN(host) -> STLS(device) -> STLS(host) -> TLS (cliente) -> CNXN(device) -> OPEN shell: -> OKAY/WRTE/CLSE."""
import os, socket, struct, subprocess, sys, threading, datetime
from OpenSSL import SSL, crypto
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa

ADB = os.environ.get('ADB') or __import__('shutil').which('adb') or 'adb'
A = dict(CNXN=0x4e584e43, OPEN=0x4e45504f, OKAY=0x59414b4f, CLSE=0x45534c43, WRTE=0x45545257, AUTH=0x48545541, STLS=0x534C5453)
NAME = {v: k for k, v in A.items()}

key = rsa.generate_private_key(65537, 2048)
nm = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u'fake-adbd')])
cert = (x509.CertificateBuilder().subject_name(nm).issuer_name(nm).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime(2020, 1, 1)).not_valid_after(datetime.datetime(2049, 1, 1)).sign(key, hashes.SHA256()))
ctx = SSL.Context(SSL.TLS_SERVER_METHOD)
ctx.set_min_proto_version(SSL.TLS1_3_VERSION); ctx.set_max_proto_version(SSL.TLS1_3_VERSION)
ctx.use_certificate(crypto.X509.from_cryptography(cert)); ctx.use_privatekey(crypto.PKey.from_cryptography_key(key))
peer = {}
def verify(conn, c, err, depth, ok):
    peer['subject'] = c.get_subject().CN   # (DeprecationWarning inofensivo)
    peer['pubkey_bits'] = c.get_pubkey().bits()
    return True
ctx.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, verify)

srv = socket.socket(); srv.bind(('127.0.0.1', 0)); srv.listen(1)
port = srv.getsockname()[1]
log = []


def rd(c, n):
    b = b''
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b


def read_msg(c):
    h = rd(c, 24)
    cmd, a0, a1, ln, chk, magic = struct.unpack('<6I', h)
    assert magic == cmd ^ 0xffffffff, 'magic'
    data = rd(c, ln) if ln else b''
    assert chk in (0, sum(data) & 0xffffffff), ('checksum', chk, sum(data))
    return cmd, a0, a1, data


def send(c, cmd, a0, a1, data=b''):
    c.sendall(struct.pack('<6I', cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data)


def serve():
    s, _ = srv.accept()
    try:
        cmd, a0, a1, data = read_msg(s)
        log.append(f'host->{NAME.get(cmd)} v={a0:#x} max={a1} banner={data[:40]!r}')
        send(s, A['STLS'], 0x01000000, 0)
        cmd, a0, a1, data = read_msg(s)
        log.append(f'host->{NAME.get(cmd)} v={a0:#x}')
        c = SSL.Connection(ctx, s); c.set_accept_state(); c.do_handshake()
        log.append(f'TLS {c.get_protocol_version_name()} cliente-cert CN={peer.get("subject")} bits={peer.get("pubkey_bits")}')
        send(c, A['CNXN'], 0x01000001, 1 << 20, b'device::ro.product.name=fake;features=shell_v2,cmd\0')
        while True:
            cmd, a0, a1, data = read_msg(c)
            log.append(f'host->{NAME.get(cmd)} a0={a0} a1={a1} data={data[:30]!r}')
            if cmd == A['OPEN']:
                send(c, A['OKAY'], 100, a0)
                send(c, A['WRTE'], 100, a0, b'hola desde el adbd falso\n')
            elif cmd == A['OKAY']:
                send(c, A['CLSE'], 100, a0)
            elif cmd == A['CLSE']:
                break
    except Exception as e:
        log.append('fin: ' + repr(e))


t = threading.Thread(target=serve, daemon=True); t.start()
env = dict(os.environ)
r = subprocess.run([ADB, 'connect', f'127.0.0.1:{port}'], capture_output=True, text=True, timeout=30)
print('adb connect ->', (r.stdout + r.stderr).strip())
r = subprocess.run([ADB, '-s', f'127.0.0.1:{port}', 'shell', 'echo hi'], capture_output=True, text=True, timeout=30)
print('adb shell ->', (r.stdout + r.stderr).strip())
t.join(5)
subprocess.run([ADB, 'disconnect', f'127.0.0.1:{port}'], capture_output=True, text=True)
print('\n'.join(log))
