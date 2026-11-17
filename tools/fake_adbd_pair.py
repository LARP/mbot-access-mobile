"""Servidor de emparejamiento de adb FALSO (rol servidor) hecho con la referencia Python (adb_pairing_reference.py), para probar contra el `adb pair`
REAL (BoringSSL) del PC: `python tools/fake_adbd_pair.py [CODIGO]`. Requiere: pip install pyopenssl cryptography. Con el codigo 482913 empareja; con otro falla.
Valida el protocolo que implementa android/.../adb/PairingProtocol.kt (SPAKE2, HKDF, AES-GCM, marcos, exportador TLS 'adb-label')."""
import os, socket, subprocess, sys, threading, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from OpenSSL import SSL, crypto
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import adb_pairing_reference as V

CODE = b'482913'
ADB = os.environ.get('ADB') or __import__('shutil').which('adb') or 'adb'
CN = b'adb pair client\0'; SN = b'adb pair server\0'

key = rsa.generate_private_key(65537, 2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u'fake-adbd')])
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime(2020, 1, 1)).not_valid_after(datetime.datetime(2049, 1, 1)).sign(key, hashes.SHA256()))
ctx = SSL.Context(SSL.TLS_SERVER_METHOD)
ctx.set_min_proto_version(SSL.TLS1_3_VERSION); ctx.set_max_proto_version(SSL.TLS1_3_VERSION)
ctx.use_certificate(crypto.X509.from_cryptography(cert)); ctx.use_privatekey(crypto.PKey.from_cryptography_key(key))
ctx.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, lambda *a: True)

srv = socket.socket(); srv.bind(('127.0.0.1', 0)); srv.listen(1)
port = srv.getsockname()[1]
result = {}


def recv_exact(c, n):
    b = b''
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError('cerrado')
        b += x
    return b


def serve():
    s, _ = srv.accept()
    c = SSL.Connection(ctx, s); c.set_accept_state()
    try:
        c.do_handshake()
        exp = c.export_keying_material(b'adb-label\0', 64)
        pw = CODE + exp
        sp = V.Spake(False, SN, CN, os.urandom(64))
        my = sp.gen(pw)
        hdr = recv_exact(c, 6)
        assert hdr[0] == 1 and hdr[1] == 0, hdr
        n = int.from_bytes(hdr[2:], 'big'); theirs = recv_exact(c, n)
        c.sendall(bytes([1, 0]) + (len(my)).to_bytes(4, 'big') + my)
        keymat = sp.process(theirs)
        aes = AESGCM(V.hkdf(keymat, b'adb pairing_auth aes-128-gcm key', 16))
        hdr = recv_exact(c, 6); assert hdr[1] == 1, hdr
        n = int.from_bytes(hdr[2:], 'big'); enc = recv_exact(c, n)
        peer = aes.decrypt((0).to_bytes(8, 'little') + b'\0' * 4, enc, None)     # falla si el codigo/exportador no coinciden
        result['peer_type'] = peer[0]; result['peer_key'] = peer[1:].split(b'\0')[0].decode()
        info = bytes(8192); info = bytes([1]) + b'FAKE-GUID' + bytes(8192 - 10)
        out = aes.encrypt((0).to_bytes(8, 'little') + b'\0' * 4, info, None)
        c.sendall(bytes([1, 1]) + len(out).to_bytes(4, 'big') + out)
        result['ok'] = True
    except Exception as e:
        result['err'] = repr(e)
    finally:
        try: c.shutdown()
        except Exception: pass


t = threading.Thread(target=serve); t.start()
code = sys.argv[1].encode() if len(sys.argv) > 1 else CODE
r = subprocess.run([ADB, 'pair', f'127.0.0.1:{port}', code.decode()], capture_output=True, text=True, timeout=40)
t.join(10)
print('adb pair ->', (r.stdout + r.stderr).strip())
print('servidor ->', {k: (v[:40] + '...' if isinstance(v, str) and len(v) > 40 else v) for k, v in result.items()})
