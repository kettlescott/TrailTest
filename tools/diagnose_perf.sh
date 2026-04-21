#!/usr/bin/env bash
#
# diagnose_perf.sh — "cpu-migrations / cache-misses 为什么看不到？" 一键体检
#
# 用法：bash tools/diagnose_perf.sh results/shared_short/shared_short.perf.data
#

set -u
PERFDATA="${1:-}"
if [[ -z "$PERFDATA" || ! -f "$PERFDATA" ]]; then
  echo "用法: $0 <path/to/perf.data>"
  echo "例如: $0 results/shared_short/shared_short.perf.data"
  exit 1
fi

sep() { printf '\n=======================================\n%s\n=======================================\n' "$1"; }

sep "[1/5] 文件 size / 基本信息"
ls -lh "$PERFDATA"

sep "[2/5] perf.data 里实际录到的 events (perf evlist)"
perf evlist -i "$PERFDATA" 2>&1 || {
  echo "❌ perf evlist 失败 — 文件损坏或 perf 版本不匹配"; exit 2;
}

sep "[3/5] 每个 event 的样本计数 (期望每个都 > 0)"
perf script -i "$PERFDATA" --ns 2>/dev/null \
  | awk '{print $5}' | sort | uniq -c | sort -rn \
  | head -30

sep "[4/5] cpu-migrations / cache-misses 专项 (如果 Step 3 里缺就会空)"
echo "--- cpu-migrations ---"
perf report -i "$PERFDATA" --stdio -e cpu-migrations --sort=comm,symbol 2>/dev/null \
  | head -20
echo
echo "--- cache-misses ---"
perf report -i "$PERFDATA" --stdio -e cache-misses --sort=comm,symbol 2>/dev/null \
  | head -20

sep "[5/5] 这台机器原生支持哪些 event (PMU 能力)"
echo "--- 硬件事件可用性 (用 /bin/true 跑一秒探测) ---"
perf stat -e cycles,instructions,cache-references,cache-misses,LLC-loads,LLC-load-misses,cpu-migrations,context-switches true 2>&1 \
  | grep -E "cycles|instructions|cache|LLC|cpu-migrations|context-switches|supported|counted"

echo
echo "--- perf_event_paranoid (需 <= -1 才能拿 futex/sched tracepoint) ---"
sysctl kernel.perf_event_paranoid 2>/dev/null || cat /proc/sys/kernel/perf_event_paranoid
echo "--- ptrace_scope ---"
sysctl kernel.yama.ptrace_scope 2>/dev/null || echo "(N/A)"

echo
sep "结论提示"
cat <<'EOF'
• Step 2 (evlist) 里有事件名但 Step 3 里样本数为 0  →  那台机器 PMU / 权限不够
• Step 2 里根本没列出某事件                        →  录制时就失败了，看 *.perf.log
• Step 3 里都有样本但 perf report 之前看不到       →  要加 -e <event>，见 Step 4
• Step 5 里出现 <not supported>                     →  VM / 容器限制，换裸金属或给 CAP_PERFMON
• perf_event_paranoid > -1                          →  sudo sysctl -w kernel.perf_event_paranoid=-1
EOF

