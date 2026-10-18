#!/usr/bin/env python3.11
"""可视化（Python，仅离线分析，绝不进运行时热路径）。

用法：
  python plot.py --jsonl run.jsonl --out traj.png
      画单次推演轨迹：上=世界维度，下=各神命途进度。
  python plot.py --seeds-dist seeds.jsonl.dist.json --out dist.png
      画多世界终局分布柱状图。
"""
from __future__ import annotations
import argparse
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

_CN = None
for cand in ["Noto Sans CJK SC", "Noto Sans CJK JP", "WenQuanYi Zen Hei", "SimHei", "Microsoft YaHei"]:
    try:
        font_manager.findfont(cand, fallback_to_default=False)
        _CN = cand
        break
    except Exception:
        continue
if _CN:
    plt.rcParams["font.family"] = _CN
plt.rcParams["axes.unicode_minus"] = False

DIM_CN = {"order":"秩序","entropy":"熵","life":"生命","knowledge":"知识",
          "resources":"资源","stability":"稳定"}
DIM_COLORS = {"order":"#1f77b4","entropy":"#d62728","life":"#2ca02c","knowledge":"#9467bd",
              "resources":"#ff7f0e","stability":"#17becf"}
GOD_PALETTE = ["#1f77b4","#d62728","#2ca02c","#9467bd","#ff7f0e","#17becf",
               "#8c564b","#e377c2","#7f7f7f","#bcbd22","#393b79","#637939"]


def plot_trajectory(jsonl_path, out_path, max_ticks_display=600):
    ts, states, progs = [], [], []
    with open(jsonl_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            ts.append(o["t"])
            states.append(o["state"])
            progs.append(o["progress"])

    if not ts:
        print("空 jsonl"); return
    dims = list(states[0].keys())
    gods = list(progs[0].keys())

    if len(ts) > max_ticks_display:
        step = len(ts) // max_ticks_display
        idx = list(range(0, len(ts), step)) + [len(ts) - 1]
        idx = sorted(set(idx))
    else:
        idx = list(range(len(ts)))
    ts_d = [ts[i] for i in idx]
    states_d = [states[i] for i in idx]
    progs_d = [progs[i] for i in idx]

    fig, ax = plt.subplots(2, 1, figsize=(12, 9), sharex=True)
    a = ax[0]
    for d in dims:
        ys = [s[d] for s in states_d]
        a.plot(ts_d, ys, label=DIM_CN.get(d, d), color=DIM_COLORS.get(d), lw=1.4)
    a.axhline(1.1, ls="--", color="gray", lw=0.7)
    a.axhline(-0.1, ls="--", color="gray", lw=0.7)
    a.set_ylabel("世界维度")
    a.set_title("推演轨迹 · 世界状态")
    a.legend(ncol=6, fontsize=8)
    a.grid(alpha=0.3)

    a = ax[1]
    for i, g in enumerate(gods):
        ys = [p[g] for p in progs_d]
        a.plot(ts_d, ys, label=g, color=GOD_PALETTE[i % len(GOD_PALETTE)], lw=1.4)
    a.axhline(1.0, ls="--", color="red", lw=0.7, label="命途极致线")
    a.set_xlabel("tick"); a.set_ylabel("命途进度")
    a.set_title("推演轨迹 · 各神命途进度")
    a.legend(ncol=4, fontsize=8)
    a.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"轨迹图 -> {out_path}")


def plot_distribution(dist_json, out_path):
    d = json.load(open(dist_json, encoding="utf-8"))
    dist = d["dist"]
    # 排序：命途极致类在前
    items = sorted(dist.items(), key=lambda kv: -kv[1])
    labels = [k for k, _ in items]
    vals = [v for _, v in items]
    n = d.get("n", sum(vals))
    fig, ax = plt.subplots(figsize=(11, 5))
    bars = ax.bar(range(len(labels)), vals, color="#4c72b0")
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=20, ha="right", fontsize=8)
    ax.set_ylabel("出现次数")
    ax.set_title(f"多世界并行 · 终局分布（{n} 个 seed）")
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v + 0.3, str(v), ha="center", fontsize=8)
    ax.grid(alpha=0.3, axis="y")
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"分布图 -> {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonl")
    ap.add_argument("--seeds-dist")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if args.jsonl:
        plot_trajectory(args.jsonl, args.out)
    elif args.seeds_dist:
        plot_distribution(args.seeds_dist, args.out)
    else:
        print("需指定 --jsonl 或 --seeds-dist")


if __name__ == "__main__":
    main()
