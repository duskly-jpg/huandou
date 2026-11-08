#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统计 APK 里每个方法的代码大小，筛出适合 True-VMP 的候选。

用法：
    python3 tools/method_size.py 幻豆_1.8.2.apk
    python3 tools/method_size.py 幻豆_1.8.2.apk --max 500 --min 24
    python3 tools/method_size.py 幻豆_1.8.2.apk --max 500 --json > sizes.json

为什么要有这个东西：
    True-VMP 对小方法是编译器级的等价翻译，风险低；对三、四千字节、带 try/finally
    和匿名内部类的大方法，一旦语义有偏差，表现不是崩溃而是静默失败——通知一闪、
    下载不动，查起来极费劲。所以打包前先看清楚每个方法多大，再决定给谁开。

    打包器那边已经支持 --true-vmp-max-bytes <n> 做硬阈值，这个脚本的用处是
    「在开之前先看看这条线切在哪」，以及事后核对到底哪些方法被放进了解释器。

判定口径：
    size     = insns size（16-bit 单元）× 2，单位字节
    catches  = try/catch handler 数量，>0 表示有异常表
    monitor  = 含 monitor-enter / monitor-exit（synchronized 块）
    custom   = 含 invoke-custom / invoke-polymorphic（lambda、方法句柄）
"""

import os
import re
import sys
import json
import shutil
import zipfile
import tempfile
import subprocess
from collections import defaultdict

DEFAULT_DEXDUMP = "/opt/android-sdk/build-tools/34.0.0/dexdump"

RE_NAME = re.compile(r"^name\s+:\s+'(.*)'$")
RE_SIZE = re.compile(r"^insns size\s+:\s+(\d+)\s+16-bit code units")
RE_CATCH = re.compile(r"^catches\s+:\s+(\d+)$")
RE_CLASS = re.compile(r"^Class descriptor\s+:\s+'(.*)'$")


def find_dexdump():
    p = os.environ.get("DEXDUMP") or DEFAULT_DEXDUMP
    if os.path.exists(p):
        return p
    for base in ("/opt/android-sdk/build-tools", os.path.expanduser("~/Android/Sdk/build-tools")):
        if os.path.isdir(base):
            for v in sorted(os.listdir(base), reverse=True):
                cand = os.path.join(base, v, "dexdump")
                if os.path.exists(cand):
                    return cand
    sys.exit("找不到 dexdump，用 DEXDUMP=/path/to/dexdump 指定")


def dex_files(apk, workdir):
    """把 APK 里的 classes*.dex 解到临时目录。"""
    out = []
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if re.match(r"^classes\d*\.dex$", n)]
        names.sort()
        for n in names:
            dst = os.path.join(workdir, os.path.basename(n))
            with z.open(n) as src, open(dst, "wb") as f:
                shutil.copyfileobj(src, f)
            out.append(dst)
    return out


def parse_dex(dexdump, path):
    """解析 dexdump -d 的文本输出。

    不去自己解 dex 格式，是因为 dexdump 是官方工具、输出稳定，
    而 dex 的 class_data / code_item 变长编码自己写容易出隐蔽错误。

    注意顺序：insns size 在方法头，指令行在其后。所以不能一见 size 就收，
    得等下一个 name / class 出现再入栈——否则统计指令特征时方法已经出栈了。
    """
    text = subprocess.run([dexdump, "-d", path],
                          capture_output=True, text=True, errors="replace").stdout
    methods = []
    cls = "?"
    cur = None

    def flush():
        nonlocal cur
        if cur is not None and cur["_sized"]:
            methods.append(cur)
        cur = None

    for raw in text.splitlines():
        s = raw.strip()
        m = RE_CLASS.match(s)
        if m:
            flush()
            cls = m.group(1)
            continue
        m = RE_NAME.match(s)
        if m:
            # 字段也有 name 行，但它后面不会有 insns size，flush 时按 _sized 丢掉。
            flush()
            cur = {"cls": cls, "name": m.group(1), "size": 0,
                   "catches": 0, "monitor": False, "custom": False,
                   "arrays": 0, "longop": 0, "_sized": False}
            continue
        if cur is None:
            continue
        m = RE_SIZE.match(s)
        if m:
            cur["size"] = int(m.group(1)) * 2
            cur["_sized"] = True
            continue
        m = RE_CATCH.match(s)
        if m:
            cur["catches"] = int(m.group(1))
            continue
        if "monitor-enter" in s or "monitor-exit" in s:
            cur["monitor"] = True
        elif "invoke-custom" in s or "invoke-polymorphic" in s:
            cur["custom"] = True
        # 2026-11 教训：ivFromSeq 只有几十字节，完全符合「小、无异常表、无锁」，
        # 但 new byte[16] 经 VMP 翻译后成了长度 1 的数组，AES-128 分片全解不开。
        # 数组创建和 64 位运算才是真正的风险源，单独计数。
        if "new-array" in s or "anewarray" in s or "filled-new-array" in s:
            cur["arrays"] += 1
        if "-long" in s:
            cur["longop"] += 1
    flush()
    return methods


def short_type(desc):
    """Lcom/foo/Bar; -> com.foo.Bar"""
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return desc


def main():
    args = [a for a in sys.argv[1:]]
    if not args:
        print(__doc__)
        sys.exit(1)
    apk = args[0]
    limit = 500
    floor = 24
    as_json = "--json" in args
    detail = "--detail" in args
    if "--max" in args:
        limit = int(args[args.index("--max") + 1])
    if "--min" in args:
        floor = int(args[args.index("--min") + 1])

    dexdump = find_dexdump()
    work = tempfile.mkdtemp(prefix="dexsize_")
    try:
        allm = []
        for d in dex_files(apk, work):
            allm.extend(parse_dex(dexdump, d))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # 去重：同一个方法在多个 dex 里出现的概率极低，但 multidex 边界上可能重复。
    seen = set()
    uniq = []
    for m in allm:
        k = (m["cls"], m["name"], m["size"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(m)

    ok = [m for m in uniq if floor <= m["size"] <= limit
          and m["catches"] == 0 and not m["monitor"] and not m["custom"]]
    big = [m for m in uniq if m["size"] > limit]

    if as_json:
        json.dump({"total": len(uniq), "limit": limit, "floor": floor,
                   "eligible": ok, "over_limit": big},
                  sys.stdout, ensure_ascii=False, indent=2)
        print()
        return

    print("APK  : %s" % os.path.basename(apk))
    print("方法 : 共 %d 个 · ≤%dB 且无异常表/无 monitor/无 lambda 的 %d 个 · >%dB 的 %d 个"
          % (len(uniq), limit, len(ok), limit, len(big)))
    print()

    by_cls = defaultdict(list)
    for m in ok:
        by_cls[m["cls"]].append(m)

    print("## 按类聚合（只列符合条件的）")
    print()
    print("| 类 | 方法数 | 字节合计 | 最大 | 含数组/64位 |")
    print("|---|---|---|---|---|")
    for c in sorted(by_cls, key=lambda x: -sum(m["size"] for m in by_cls[x])):
        ms = by_cls[c]
        risky = sum(1 for m in ms if m["arrays"] or m["longop"])
        print("| %s | %d | %d | %d | %s |" % (short_type(c), len(ms),
                                         sum(m["size"] for m in ms),
                                         max(m["size"] for m in ms),
                                         ("%d 个 ⚠" % risky) if risky else "—"))
    print()

    if detail:
        print("## 明细（按大小降序）")
        print()
        print("| 字节 | 类 | 方法 | 数组 | 64位 |")
        print("|---|---|---|---|---|")
        for m in sorted(ok, key=lambda x: -x["size"]):
            print("| %d | %s | %s | %d | %d |" % (m["size"], short_type(m["cls"]),
                                                 m["name"], m["arrays"], m["longop"]))
        print()

    if big:
        print("## 超阈值、会被跳过的方法（前 15）")
        print()
        print("| 字节 | 类 | 方法 | 原因 |")
        print("|---|---|---|---|")
        for m in sorted(big, key=lambda x: -x["size"])[:15]:
            why = []
            if m["catches"]:
                why.append("try/catch×%d" % m["catches"])
            if m["monitor"]:
                why.append("synchronized")
            if m["custom"]:
                why.append("lambda")
            if not why:
                why.append("仅超大小")
            print("| %d | %s | %s | %s |" % (m["size"], short_type(m["cls"]),
                                             m["name"], "、".join(why)))


if __name__ == "__main__":
    main()
