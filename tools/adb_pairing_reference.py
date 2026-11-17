"""[Herramienta de desarrollo] Referencia independiente (enteros de Python, formulas afines de ed25519.py) del SPAKE2 de BoringSSL + HKDF/AES-GCM de adb + formato de clave publica de adb.
Genera los vectores que usan las pruebas Kotlin."""
import hashlib, base64, os, struct, hmac
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

q = 2**255 - 19
l = 2**252 + 27742317777372353535851937790883648493
d = -121665 * pow(121666, q - 2, q) % q
I = pow(2, (q - 1) // 4, q)


def inv(x): return pow(x, q - 2, q)


def xrecover(y):
    xx = (y * y - 1) * inv(d * y * y + 1)
    x = pow(xx, (q + 3) // 8, q)
    if (x * x - xx) % q != 0: x = x * I % q
    if (x * x - xx) % q != 0: raise ValueError('no punto')
    return x


def decode(b):
    y = int.from_bytes(b, 'little'); sign = y >> 255; y &= (1 << 255) - 1
    x = xrecover(y)
    if (x & 1) != sign: x = q - x
    return (x, y)


def encode(P):
    x, y = P
    return (y | ((x & 1) << 255)).to_bytes(32, 'little')


def add(P, Q):
    x1, y1 = P; x2, y2 = Q
    x3 = (x1 * y2 + x2 * y1) * inv(1 + d * x1 * x2 * y1 * y2) % q
    y3 = (y1 * y2 + x1 * x2) * inv(1 - d * x1 * x2 * y1 * y2) % q
    return (x3, y3)


def neg(P): return ((-P[0]) % q, P[1])


def mul(P, e):
    R = (0, 1)
    while e:
        if e & 1: R = add(R, P)
        P = add(P, P); e >>= 1
    return R


B = decode(bytes.fromhex('58' + '66' * 31))
M = decode(bytes.fromhex('5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e'))
N = decode(bytes.fromhex('10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778'))


def lp(b): return len(b).to_bytes(8, 'little') + b


class Spake:
    def __init__(self, alice, my, their, rnd):
        self.alice, self.my, self.their = alice, my, their
        self.priv = (int.from_bytes(rnd, 'little') % l) * 8
        self.rnd = rnd

    def gen(self, pw):
        P = mul(B, self.priv)
        self.pwh = hashlib.sha512(pw).digest()
        w = int.from_bytes(self.pwh, 'little') % l
        for bit in (1, 2, 4):
            if w & bit: w += l * bit
        assert w % 8 == 0
        self.w = w
        mask = mul(M if self.alice else N, w)
        self.msg = encode(add(P, mask))
        return self.msg

    def process(self, theirs):
        Qs = decode(theirs)
        pm = mul(N if self.alice else M, self.w)
        Qc = add(Qs, neg(pm))
        dh = encode(mul(Qc, self.priv))
        h = hashlib.sha512()
        if self.alice:
            for x in (self.my, self.their, self.msg, theirs): h.update(lp(x))
        else:
            for x in (self.their, self.my, theirs, self.msg): h.update(lp(x))
        h.update(lp(dh)); h.update(lp(self.pwh))
        return h.digest()


def hkdf(ikm, info, n):
    prk = hmac.new(b'\0' * 32, ikm, hashlib.sha256).digest()
    t = b''; out = b''; i = 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:n]


if __name__ == '__main__':
    # --- constantes M, N, B (x, y) frente al comentario de BoringSSL
    print('N.x', N[0]); print('N.y', N[1]); print('M.x', M[0]); print('M.y', M[1])
    # --- SPAKE2
    CN = b'adb pair client\0'; SN = b'adb pair server\0'
    pw = b'123456' + bytes(range(64))
    ra = bytes((i * 7 + 1) & 255 for i in range(64)); rb = bytes((i * 11 + 5) & 255 for i in range(64))
    a = Spake(True, CN, SN, ra); b = Spake(False, SN, CN, rb)
    ma = a.gen(pw); mb = b.gen(pw)
    ka = a.process(mb); kb = b.process(ma)
    assert ka == kb
    print('RA', ra.hex()); print('RB', rb.hex())
    print('MSG_CLIENT', ma.hex()); print('MSG_SERVER', mb.hex()); print('KEY', ka.hex())
    # --- AES clave y cifrado (nonce contador LE 8 bytes + 4 ceros)
    k = hkdf(ka, b'adb pairing_auth aes-128-gcm key', 16)
    print('AESKEY', k.hex())
    pt = b'hola adb' * 4
    ct0 = AESGCM(k).encrypt((0).to_bytes(8, 'little') + b'\0' * 4, pt, None)
    ct1 = AESGCM(k).encrypt((1).to_bytes(8, 'little') + b'\0' * 4, pt, None)
    print('PT', pt.hex()); print('CT0', ct0.hex()); print('CT1', ct1.hex())
    # --- clave publica de adb para una clave RSA fija
    key = rsa.generate_private_key(65537, 2048)
    nums = key.public_key().public_numbers()
    n = nums.n
    n0inv = (-pow(n & 0xffffffff, -1, 1 << 32)) & 0xffffffff
    rr = pow(2, 4096, n)
    blob = struct.pack('<II', 64, n0inv) + n.to_bytes(256, 'little') + rr.to_bytes(256, 'little') + struct.pack('<I', 65537)
    print('RSA_N', format(n, 'x')); print('ADBKEY_B64', base64.b64encode(blob).decode())
    # --- HKDF RFC 5869 caso 3 (sal vacia) lo cubre la prueba Kotlin con el vector del RFC
