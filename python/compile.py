#!/usr/bin/env python3.11
"""编译时工具：把 JSON 逻辑源码里的「表达式字符串」编译成 AST JSON。

职责（严格不进运行时热路径）：
  1. 校验：函数名必须白名单；标识符只允许 [A-Za-z_][A-Za-z0-9_]*（无成员访问，
     从结构上杜绝 os.system 这类注入）。
  2. 编译：表达式字符串 -> AST 节点（见 AST 说明）。
  3. 产出：build/logic/world.json、build/logic/gods/<name>.json、build/logic/manifest.json。

AST 节点约定（Java Expr 解释器对应读取）：
  {"t":"num","v":1.2}
  {"t":"var","n":"entropy"}
  {"t":"neg","a":node}           一元负号
  {"t":"not","a":node}           一元逻辑非
  {"t":"add"|"sub"|"mul"|"div"|"mod","a":node,"b":node}
  {"t":"lt"|"le"|"gt"|"ge"|"eq"|"ne","a":node,"b":node}
  {"t":"and"|"or","a":node,"b":node}
  {"t":"call","f":"abs","args":[node,...]}
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys

# 白名单函数：只允许这些，且禁止成员访问 —— 这是防注入的关键
ALLOWED_FUNCS = {
    "sin", "cos", "tan", "asin", "acos", "atan",
    "exp", "log", "log10", "sqrt", "abs", "sign", "floor", "ceil", "round",
    "min", "max", "pow", "clamp", "tanh", "mod", "hypot",
}

# ───────────────────────── 词法 ─────────────────────────
class Tok:
    def __init__(self, kind, val):
        self.kind = kind
        self.val = val

def tokenize(s: str):
    toks = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c.isspace():
            i += 1; continue
        if c.isdigit() or (c == '.' and i + 1 < n and s[i+1].isdigit()):
            j = i
            while j < n and (s[j].isdigit() or s[j] == '.'):
                j += 1
            if j < n and s[j] in 'eE':
                j += 1
                if j < n and s[j] in '+-':
                    j += 1
                while j < n and s[j].isdigit():
                    j += 1
            toks.append(Tok("num", float(s[i:j]))); i = j; continue
        if c.isalpha() or c == '_':
            j = i
            while j < n and (s[j].isalnum() or s[j] == '_'):
                j += 1
            toks.append(Tok("id", s[i:j])); i = j; continue
        two = s[i:i+2]
        if two in ("<=", ">=", "==", "!=", "&&", "||"):
            toks.append(Tok(two, two)); i += 2; continue
        if c in "+-*/%()<>,!":
            toks.append(Tok(c, c)); i += 1; continue
        raise ValueError(f"无法识别的字符：{c!r}（在 {s!r}）")
    toks.append(Tok("eof", None))
    return toks


# ───────────────────────── 语法（递归下降） ─────────────────────────
class Parser:
    def __init__(self, toks):
        self.toks = toks
        self.p = 0

    def peek(self):
        return self.toks[self.p]

    def next(self):
        t = self.toks[self.p]; self.p += 1; return t

    def expect(self, kind):
        t = self.next()
        if t.kind != kind:
            raise ValueError(f"期望 {kind}，实际 {t.kind}({t.val})")
        return t

    def parse(self):
        node = self.parse_or()
        if self.peek().kind != "eof":
            raise ValueError(f"多余 token：{self.peek().val}")
        return node

    def parse_or(self):
        left = self.parse_and()
        while self.peek().kind == "||":
            self.next(); right = self.parse_and()
            left = {"t": "or", "a": left, "b": right}
        return left

    def parse_and(self):
        left = self.parse_cmp()
        while self.peek().kind == "&&":
            self.next(); right = self.parse_cmp()
            left = {"t": "and", "a": left, "b": right}
        return left

    def parse_cmp(self):
        left = self.parse_add()
        while self.peek().kind in ("<", "<=", ">", ">=", "==", "!="):
            op = self.next().kind
            right = self.parse_add()
            left = {"t": op, "a": left, "b": right}
        return left

    def parse_add(self):
        left = self.parse_mul()
        while self.peek().kind in ("+", "-"):
            op = self.next().kind
            right = self.parse_mul()
            left = {"t": "add" if op == "+" else "sub", "a": left, "b": right}
        return left

    def parse_mul(self):
        left = self.parse_unary()
        while self.peek().kind in ("*", "/", "%"):
            op = self.next().kind
            right = self.parse_unary()
            m = {"*": "mul", "/": "div", "%": "mod"}[op]
            left = {"t": m, "a": left, "b": right}
        return left

    def parse_unary(self):
        t = self.peek()
        if t.kind == "-":
            self.next(); return {"t": "neg", "a": self.parse_unary()}
        if t.kind == "!":
            self.next(); return {"t": "not", "a": self.parse_unary()}
        return self.parse_primary()

    def parse_primary(self):
        t = self.peek()
        if t.kind == "(":
            self.next(); node = self.parse_or(); self.expect(")"); return node
        if t.kind == "num":
            self.next(); return {"t": "num", "v": t.val}
        if t.kind == "id":
            self.next()
            if self.peek().kind == "(":
                self.next()
                args = []
                if self.peek().kind != ")":
                    args.append(self.parse_or())
                    while self.peek().kind == ",":
                        self.next(); args.append(self.parse_or())
                self.expect(")")
                if t.val not in ALLOWED_FUNCS:
                    raise ValueError(f"禁止的函数调用：{t.val}()（不在白名单）")
                return {"t": "call", "f": t.val, "args": args}
            return {"t": "var", "n": t.val}
        raise ValueError(f"无法解析 token：{t.kind}({t.val})")


def compile_expr(s: str) -> dict:
    """编译一条表达式字符串为 AST 节点。"""
    toks = tokenize(s)
    return Parser(toks).parse()


def compile_god(god: dict) -> dict:
    out = dict(god)  # 拷贝标量/对象字段
    # perceive：map var -> expr
    out["perceive"] = {k: compile_expr(v) for k, v in god.get("perceive", {}).items()}
    # modes：每个 mode 有 score(expr) 和 deltas(map dim->expr)
    modes = {}
    for mname, m in god.get("modes", {}).items():
        modes[mname] = {
            "score": compile_expr(m["score"]),
            "deltas": {d: compile_expr(e) for d, e in m.get("deltas", {}).items()},
        }
    out["modes"] = modes
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.path.join(os.path.dirname(__file__), "..", "logic"))
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "build", "logic"))
    args = ap.parse_args()

    src = os.path.abspath(args.src)
    out = os.path.abspath(args.out)
    os.makedirs(os.path.join(out, "gods"), exist_ok=True)

    world = json.load(open(os.path.join(src, "world.json"), encoding="utf-8"))

    god_files = sorted(glob.glob(os.path.join(src, "gods", "*.json")))
    if not god_files:
        print("错误：logic/gods/ 下没有神的 JSON", file=sys.stderr); sys.exit(1)

    names = []
    for gf in god_files:
        god = json.load(open(gf, encoding="utf-8"))
        cg = compile_god(god)
        name = cg["name"]
        names.append(name)
        with open(os.path.join(out, "gods", f"{name}.json"), "w", encoding="utf-8") as f:
            json.dump(cg, f, ensure_ascii=False)
        print(f"  编译神 [{name}]  OK")

    # world.json 原样拷贝到 build（Java/C 启动用）
    with open(os.path.join(out, "world.json"), "w", encoding="utf-8") as f:
        json.dump(world, f, ensure_ascii=False)

    manifest = {
        "dims": world["dims"],
        "relax": world["relax"],
        "soft_lo": world["soft_lo"], "soft_hi": world["soft_hi"],
        "hard_lo": world["hard_lo"], "hard_hi": world["hard_hi"],
        "default_state": world["default_state"],
        "gods": names,
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False)

    print(f"编译完成：{len(names)} 个神 -> {out}")
    print(f"  dims = {manifest['dims']}")


if __name__ == "__main__":
    main()
