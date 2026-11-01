import Foundation
import CryptoKit

enum JMCrypto {
    static func md5Hex(_ s: String) -> String {
        let digest = Insecure.MD5.hash(data: Data(s.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// AES-256 ECB decrypt (PKCS7), key = UTF-8 bytes of the 32-char token hex string.
    static func aesECBDecrypt(base64: String, keyString: String) -> Data? {
        guard let ct = Data(base64Encoded: base64), ct.count % 16 == 0 else { return nil }
        let key = [UInt8](keyString.utf8)
        guard key.count == 32 else { return nil }
        var aes = AES256(key: key)
        var out = [UInt8]()
        out.reserveCapacity(ct.count)
        let bytes = [UInt8](ct)
        var i = 0
        while i < bytes.count {
            let block = Array(bytes[i..<(i + 16)])
            out.append(contentsOf: aes.decryptBlock(block))
            i += 16
        }
        // strip PKCS7 padding
        if let pad = out.last, pad >= 1, pad <= 16, out.count >= Int(pad) {
            out.removeLast(Int(pad))
        }
        return Data(out)
    }
}

/// Minimal AES-256 (decrypt direction) with programmatically derived S-boxes,
/// so there are no lookup tables to mistype.
private struct AES256 {
    private var roundKeys = [[UInt8]]()  // 15 round keys of 16 bytes (state order)
    private static let sbox: [UInt8] = AES256.buildSbox()
    private static let invSbox: [UInt8] = AES256.buildInvSbox(sbox)

    init(key: [UInt8]) {
        // Key expansion for Nk=8, Nr=14 -> 60 words
        var w = [[UInt8]]()
        for i in 0..<8 { w.append(Array(key[(i * 4)..<(i * 4 + 4)])) }
        var rcon: UInt8 = 1
        for i in 8..<60 {
            var temp = w[i - 1]
            if i % 8 == 0 {
                temp = [AES256.sbox[Int(temp[1])] ^ rcon,
                        AES256.sbox[Int(temp[2])],
                        AES256.sbox[Int(temp[3])],
                        AES256.sbox[Int(temp[0])]]
                rcon = AES256.xtime(rcon)
            } else if i % 8 == 4 {
                temp = temp.map { AES256.sbox[Int($0)] }
            }
            w.append(zip(w[i - 8], temp).map { $0 ^ $1 })
        }
        for r in 0..<15 {
            var rk = [UInt8]()
            for c in 0..<4 { rk.append(contentsOf: w[r * 4 + c]) }
            roundKeys.append(rk)
        }
    }

    func decryptBlock(_ block: [UInt8]) -> [UInt8] {
        var s = block
        addRoundKey(&s, round: 14)
        for round in stride(from: 13, through: 1, by: -1) {
            invShiftRows(&s)
            invSubBytes(&s)
            addRoundKey(&s, round: round)
            invMixColumns(&s)
        }
        invShiftRows(&s)
        invSubBytes(&s)
        addRoundKey(&s, round: 0)
        return s
    }

    private func addRoundKey(_ s: inout [UInt8], round: Int) {
        let rk = roundKeys[round]
        for i in 0..<16 { s[i] ^= rk[i] }
    }

    private func invSubBytes(_ s: inout [UInt8]) {
        for i in 0..<16 { s[i] = AES256.invSbox[Int(s[i])] }
    }

    // State is column-major: index = col*4 + row
    private func invShiftRows(_ s: inout [UInt8]) {
        var t = s
        for r in 1..<4 {
            for c in 0..<4 {
                t[c * 4 + r] = s[((c - r + 4) % 4) * 4 + r]
            }
        }
        s = t
    }

    private func invMixColumns(_ s: inout [UInt8]) {
        for c in 0..<4 {
            let i = c * 4
            let a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3]
            s[i]     = AES256.gmul(a0, 14) ^ AES256.gmul(a1, 11) ^ AES256.gmul(a2, 13) ^ AES256.gmul(a3, 9)
            s[i + 1] = AES256.gmul(a0, 9) ^ AES256.gmul(a1, 14) ^ AES256.gmul(a2, 11) ^ AES256.gmul(a3, 13)
            s[i + 2] = AES256.gmul(a0, 13) ^ AES256.gmul(a1, 9) ^ AES256.gmul(a2, 14) ^ AES256.gmul(a3, 11)
            s[i + 3] = AES256.gmul(a0, 11) ^ AES256.gmul(a1, 13) ^ AES256.gmul(a2, 9) ^ AES256.gmul(a3, 14)
        }
    }

    static func xtime(_ x: UInt8) -> UInt8 {
        (x & 0x80) != 0 ? (x << 1) ^ 0x1b : (x << 1)
    }

    static func gmul(_ a: UInt8, _ b: UInt8) -> UInt8 {
        var a = a, b = b, p: UInt8 = 0
        while b != 0 {
            if b & 1 != 0 { p ^= a }
            a = xtime(a)
            b >>= 1
        }
        return p
    }

    private static func buildSbox() -> [UInt8] {
        var box = [UInt8](repeating: 0, count: 256)
        for i in 0..<256 {
            let inv = gfInverse(UInt8(i))
            let x = inv ^ rotl(inv, 1) ^ rotl(inv, 2) ^ rotl(inv, 3) ^ rotl(inv, 4) ^ 0x63
            box[i] = x
        }
        return box
    }

    private static func buildInvSbox(_ sbox: [UInt8]) -> [UInt8] {
        var inv = [UInt8](repeating: 0, count: 256)
        for i in 0..<256 { inv[Int(sbox[i])] = UInt8(i) }
        return inv
    }

    private static func rotl(_ x: UInt8, _ n: Int) -> UInt8 {
        (x << n) | (x >> (8 - n))
    }

    private static func gfInverse(_ x: UInt8) -> UInt8 {
        if x == 0 { return 0 }
        var result: UInt8 = 1
        var base = x
        var exp = 254  // a^254 = a^-1 in GF(2^8)
        var r: UInt8 = 1
        var b = base
        var e = exp
        while e > 0 {
            if e & 1 == 1 { r = gmul(r, b) }
            b = gmul(b, b)
            e >>= 1
        }
        result = r
        _ = base; _ = exp
        return result
    }
}
