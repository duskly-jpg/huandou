#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建前守卫：核对 KeyAttest.java 里的 OID 常量与偏移逻辑。

为什么需要这个脚本：
    OID 的字节是手写的，写错一个 nibble 不会编译报错、不会抛异常，
    只会让「设备有没有认证扩展」永远返回 false。这种假阴性最难发现——
    看起来像设备不支持，实际是常量错了。2026-11-08 那次就差点带着
    0xAB 0x79（正确是 0xD6 0x79）打包发出去。

两件事：
    1. 从 Java 源码里抠出 OID_KA 的字节字面量，用标准 OID 编码复算比对。
    2. 构造一段最小 KeyDescription，按 Java 里 securityLevel() 的走位
       逐字节跑一遍，确认能读到正确的安全等级。

用法：python3 tools/check_oid.py [src/com/huandou/app/KeyAttest.java]
退出码非 0 时 build.sh 会中止。
"""

import re
import sys
import os

OID_TEXT = "1.3.6.1.4.1.11129.2.1.17"
DEFAULT_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "src", "com", "huandou", "app", "KeyAttest.java")


def oid_bytes(text):
    """按 X.690 把点分 OID 编成内容字节（不含 tag/length）。"""
    parts = [int(x) for x in text.split(".")]
    out = bytearray([parts[0] * 40 + parts[1]])
    for n in parts[2:]:
        stack = [n & 0x7F]
        n >>= 7
        while n:
            stack.append((n & 0x7F) | 0x80)
            n >>= 7
        out += bytes(reversed(stack))
    return bytes(out)


def java_const(path):
    """从 Java 源码里抠 OID_KA 的花括号内容，返回 bytes。"""
    src = open(path, encoding="utf-8").read()
    m = re.search(r"byte\[\]\s+OID_KA\s*=\s*\{(.*?)\};", src, re.S)
    if not m:
        raise SystemExit("找不到 OID_KA 定义：%s" % path)
    body = m.group(1)
    # 去掉注释，避免注释里的 0xNN 被当成数据
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    vals = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", body)]
    if not vals:
        raise SystemExit("OID_KA 里没读到字节")
    return bytes(vals)


def der(tag, content):
    n = len(content)
    if n < 0x80:
        return bytes([tag, n]) + content
    ln = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([tag, 0x80 | len(ln)]) + ln + content


def key_description(level, challenge):
    """构造最小 KeyDescription。字段顺序与 Android 文档一致，共 8 项。"""
    body = der(0x02, b"\x03")                 # attestationVersion = 3
    body += der(0x0A, bytes([level]))         # attestationSecurityLevel
    body += der(0x02, b"\x04")                # keymasterVersion = 4
    body += der(0x0A, b"\x01")                # keymasterSecurityLevel
    body += der(0x04, challenge)              # attestationChallenge
    body += der(0x04, b"")                    # uniqueId
    body += der(0x30, b"")                    # softwareEnforced
    body += der(0x30, b"")                    # teeEnforced
    return der(0x30, body)


def walk_level(blob, oid_pos, oid_len):
    """复刻 KeyAttest.securityLevel() 的走位，返回读到的等级或 -1。"""
    p = oid_pos + oid_len
    if p + 2 <= len(blob) and blob[p] == 0x01:
        p += 2 + blob[p + 1]
    if p >= len(blob) or blob[p] != 0x04:
        return -1

    def read_len(d, q):
        if q >= len(d):
            return None
        b = d[q]
        if b < 0x80:
            return q + 1, b
        n = b & 0x7F
        if n == 0 or n > 3 or q + n >= len(d):
            return None
        return q + 1 + n, int.from_bytes(d[q + 1:q + 1 + n], "big")

    v = read_len(blob, p + 1)
    if v is None:
        return -1
    q = v[0]
    if q >= len(blob) or blob[q] != 0x30:
        return -1
    s = read_len(blob, q + 1)
    if s is None:
        return -1
    # 第 1 个字段 attestationVersion（INTEGER），整段跳过
    e = s[0]
    if e >= len(blob) or blob[e] != 0x02:
        return -1
    v1 = read_len(blob, e + 1)
    if v1 is None:
        return -1
    e2 = v1[0] + v1[1]
    if e2 + 3 <= len(blob) and blob[e2] == 0x0A and blob[e2 + 1] == 0x01:
        return blob[e2 + 2]
    return -1


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    got = java_const(path)

    want = b"\x06" + bytes([len(oid_bytes(OID_TEXT))]) + oid_bytes(OID_TEXT)
    if got != want:
        print("[FAIL] OID_KA 写错了")
        print("       源码 : %s" % " ".join("%02x" % b for b in got))
        print("       应为 : %s" % " ".join("%02x" % b for b in want))
        return 1
    print("[ok] OID_KA = %s" % " ".join("%02x" % b for b in got))

    ok = True
    for level in (0, 1, 2):
        # 模拟 X.509 Extension = SEQUENCE { extnID OID, extnValue OCTET STRING }
        # extnValue 里装的是完整的 KeyDescription。真实证书就长这样。
        ext = der(0x30, got + der(0x04, key_description(level, b"\xAA" * 32)))
        pos = ext.find(got)
        if pos < 0:
            print("[FAIL] 构造的扩展里找不到 OID，测试本身有问题")
            return 1
        read = walk_level(ext, pos, len(got))
        if read != level:
            print("[FAIL] securityLevel 走位错误：期望 %d，读到 %d" % (level, read))
            ok = False
        else:
            print("[ok] securityLevel 走位 level=%d" % level)

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
