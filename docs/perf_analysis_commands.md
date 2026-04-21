# Shared vs Sharded — `perf.data` 分析命令速查

本文件收录了 benchmark 跑完后，从一对 `perf.data` 产出"6 张结论表"的具体命令。
配合本项目 `PerfRecorder` 的默认产物路径使用：

```
results/shared_short/shared_short.perf.data
results/sharded_short/sharded_short.perf.data
```

所有命令假设你已经在 Linux 机器上切到项目根目录。Demo 里用 `sharded_short` 作
单个文件举例；把路径换成 `shared_short` 就能跑同一套命令对比。

---

## 🔍 先做：为什么我看不到 cpu-migrations / cache-misses？

`perf report` **默认只展示第一个 event 的视图（通常是 `cycles`）**。其他事件不是"没录"，而是没被展示出来。三步排查：

### Step 1 — 列文件里实际录到的所有事件
```bash
perf evlist -i results/shared_short/shared_short.perf.data
```
按当前 YAML 预期 13 个：
```
cycles
instructions
cache-references
cache-misses
LLC-loads
LLC-load-misses
context-switches
cpu-migrations
sched:sched_switch
sched:sched_wakeup
syscalls:sys_enter_futex
syscalls:sys_exit_futex
dummy:HG
```

### Step 2 — 每个事件的样本数（一眼看到谁为 0）
```bash
perf report -i results/shared_short/shared_short.perf.data --stat
```
或者更直接：
```bash
perf script -i results/shared_short/shared_short.perf.data --ns \
  | awk '{print $5}' | sort | uniq -c | sort -rn
```

### Step 3 — 专门看某一类事件
```bash
# cpu-migrations
perf report -i results/shared_short/shared_short.perf.data --stdio \
  -e cpu-migrations --sort=comm,dso,symbol | head

# cache-misses
perf report -i results/shared_short/shared_short.perf.data --stdio \
  -e cache-misses --sort=comm,dso,symbol | head
```

### 如果 `perf evlist` 里某些事件干脆不存在

说明那台机器的 PMU / kernel / 权限不支持。

```bash
# 看这台机器到底支持哪些事件
perf list hw cache sw | grep -iE "cache|LLC|cpu-migr"

# 快速确认谁是 <not supported>
perf stat -e cycles,instructions,cache-references,cache-misses,\
LLC-loads,LLC-load-misses,cpu-migrations,context-switches true
```

常见环境问题：

| 环境 | 症状 | 解决 |
|---|---|---|
| AWS / GCP / Azure 普通 VM | HW 事件 `<not supported>` | 换 `.metal` 实例；或只分析 SW 事件 + tracepoint |
| Docker 容器缺 `CAP_PERFMON` | HW 事件 `<not counted>` | `docker run --cap-add=PERFMON --cap-add=SYS_ADMIN` 或 `--privileged` |
| `perf_event_paranoid = 2` (默认) | tracepoint (sched/futex) 数 = 0 | `sudo sysctl -w kernel.perf_event_paranoid=-1` |
| 旧 kernel + 新 CPU | `LLC-loads` 被改名 | `perf list | grep -i llc` 找当前名字改进 YAML |
| Hyper-V / WSL2 | 整个 PMU 不可用 | 和 VM 同问题 |

> **`cpu-migrations` 和 `context-switches` 是 software events**，和 PMU 无关。
> 如果它们也缺，几乎一定是**录制失败**（perf 根本没跑起来）——
> 看 `results/<run>/<run>.perf.log` 里有无 `event ... not supported` 或
> `permission denied` 行，或者文件根本不存在 → 走 `HOW_TO_RUN.md` 里的
> "perf exited immediately" 那一条修。

---

## 前置条件（一次性设置）

### 内核
```bash
sudo sysctl -w kernel.perf_event_paranoid=-1   # 允许 tracepoint 采样（futex/sched）
sudo sysctl -w kernel.kptr_restrict=0          # 解析内核符号
sudo sysctl -w kernel.yama.ptrace_scope=0      # 某些发行版附加其他进程需要
```

### JVM（启动时加）
```
-XX:+UnlockDiagnosticVMOptions
-XX:+PreserveFramePointer
-XX:+DumpPerfMapAtExit
```

### FlameGraph 脚本（下载一次）
```bash
git clone https://github.com/brendangregg/FlameGraph.git /tmp/FlameGraph
export PATH=/tmp/FlameGraph:$PATH
```

### 时间对齐验证
```bash
# JFR 锚点
jfr print --events com.scott.AnchorEvent results/sharded_short/sharded_short.jfr

# perf 第一条 / 最后一条样本的时间戳（纳秒）
perf script -i results/sharded_short/sharded_short.perf.data --ns \
  | awk '{print $4}' | sed 's/://' | sort -n | sed -n '1p;$p'
```
JFR `start` anchor 的 `nanoTime` 应 ≥ perf 第一条时间戳；`stop` anchor ≤ 最后一条。
残差 < 几百 μs 属于正常（ring-buffer flush 延迟）。

---

## 结论表 #1 — IPC / cache-miss 汇总（硬件计数器）

**回答**：shared 和 sharded 在 CPU / 内存层级上的根本差异。

```bash
for d in shared_short sharded_short; do
  echo "=========================================="
  echo "== $d =="
  echo "=========================================="
  perf report -i results/$d/$d.perf.data --header-only --stdio 2>/dev/null \
    | grep -E "^# event|^# Samples"
done
```

完整 per-event 计数（需要 `perf report --stat`）：
```bash
for d in shared_short sharded_short; do
  echo "== $d =="
  perf report -i results/$d/$d.perf.data --stat 2>/dev/null \
    | grep -E "cycles|instructions|cache|LLC|context-switches|cpu-migrations|futex|sched"
done
```

**怎么看**：
- `instructions / cycles` = IPC。shared 显著低（< 0.8）→ 核心在等内存/锁。
- `cache-misses / cache-references` shared 高很多 → 队列头尾指针跨 core 弹射。
- `LLC-load-misses` shared 高 → DRAM 流量增加，cross-socket 时更糟。

---

## 结论表 #2 — CPU 热点火焰图

**回答**：CPU 时间落在哪些 Java / JVM / kernel 方法。

```bash
# sharded
perf script -i results/sharded_short/sharded_short.perf.data \
  | stackcollapse-perf.pl \
  | flamegraph.pl --title "sharded_short CPU flamegraph" \
  > results/sharded_short/cpu_flame.svg

# shared
perf script -i results/shared_short/shared_short.perf.data \
  | stackcollapse-perf.pl \
  | flamegraph.pl --title "shared_short CPU flamegraph" \
  > results/shared_short/cpu_flame.svg
```

**对比火焰图**（two-side diff，蓝=shared 专属，红=sharded 专属）：
```bash
perf script -i results/shared_short/shared_short.perf.data   | stackcollapse-perf.pl > /tmp/shared.folded
perf script -i results/sharded_short/sharded_short.perf.data | stackcollapse-perf.pl > /tmp/sharded.folded

difffolded.pl /tmp/shared.folded /tmp/sharded.folded \
  | flamegraph.pl --title "shared vs sharded (red=sharded hotter)" \
  > results/diff_flame.svg
```

**怎么看**：
- shared 图里 `LockSupport.park` / `AQS$ConditionObject.await` 占比高 → 锁等待。
- sharded 图里 `ShardedOnlyDispatcher.submit` 占比高 → dispatch 路径是瓶颈。

---

## 结论表 #3 — Futex 慢路径（锁竞争的直接证据）

**回答**：哪条 Java 调用栈真正进了 futex 慢路径（= `park` 实际阻塞）。

```bash
# 每个 run：futex 栈顶 30 条
for d in shared_short sharded_short; do
  echo "=========================================="
  echo "== $d : sys_enter_futex callers =="
  echo "=========================================="
  perf report -i results/$d/$d.perf.data --stdio \
    --sort=overhead,symbol \
    --call-graph=graph,0.5,caller \
    -e syscalls:sys_enter_futex 2>/dev/null | head -60
done
```

单纯的 futex 调用次数对比：
```bash
for d in shared_short sharded_short; do
  c=$(perf script -i results/$d/$d.perf.data --ns 2>/dev/null \
        | grep -c "sys_enter_futex")
  echo "$d : $c futex enters"
done
```

**怎么看**：
- shared 的 futex enter 次数应显著高于 sharded。
- 栈顶符号：`LockSupport.park` → `AQS.acquireQueued` → `ReentrantLock.lock` → 调用方（LinkedBlockingQueue.take / put 等）。
- sharded 的 futex 事件应该主要来自 worker 刚启动时的空队列 park，而非稳态竞争。

---

## 结论表 #4 — Scheduler 延迟 & wakeup 链路

**回答**：p99 queue-wait 尾延迟来自哪：用户态排队还是内核调度延迟？

### 4A — 每线程的 wake-up→on-CPU 延迟分布
```bash
# 注意：perf sched 需要在"录制时"就带上 sched:* tracepoint。
# 我们的 deep-analysis YAML 已包含 sched_switch + sched_wakeup，所以这个直接能跑。
for d in shared_short sharded_short; do
  echo "=========================================="
  echo "== $d : per-thread scheduler latency =="
  echo "=========================================="
  perf sched latency -i results/$d/$d.perf.data --sort max 2>/dev/null | head -30
done
```

### 4B — 唤醒时间线（哪个线程唤醒了谁）
```bash
perf script -i results/sharded_short/sharded_short.perf.data --ns \
  -F time,comm,pid,event \
  2>/dev/null \
  | grep -E "sched_(switch|wakeup)" | head -60
```

**怎么看**：
- `Maximum delay` 列 > 1 ms → 即使 benchmark 再优化，p99.9 也不可能低于这个值。
- shared：worker 被大量相互唤醒（generic wakeup from same core / other core）。
- sharded：wakeup 极少，说明 worker 基本不下 CPU。

---

## 结论表 #5 — CPU migration 与 NUMA 效应

**回答**：worker 有没有被 scheduler 跨 core / 跨 socket 迁移？

```bash
for d in shared_short sharded_short; do
  echo "== $d =="
  # 每个 CPU 迁移事件打印一次 CPU 号，聚合成直方图
  perf script -i results/$d/$d.perf.data \
    -F time,comm,cpu,event \
    2>/dev/null \
    | awk '/cpu-migrations/ {count[$3]++} END {for (c in count) printf "  CPU %s: %d migrations\n", c, count[c]}' \
    | sort
  echo
done
```

迁移总数对比：
```bash
for d in shared_short sharded_short; do
  n=$(perf script -i results/$d/$d.perf.data 2>/dev/null | grep -c cpu-migrations)
  echo "$d : $n migrations"
done
```

**怎么看**：
- 如果 worker 数 = core 数且线程被 pin 过，正确配置下迁移应接近 **0**。
- 如果 shared 迁移数 ≫ sharded → "shared 队列热点导致 scheduler 误判负载均衡，主动迁移线程"假设成立。
- 配合 `numastat -p <pid>`（JVM 还在运行时）可进一步看跨 socket 内存访问。

---

## 结论表 #6 — 并排硬件计数器对比（论文表格）

**回答**：把两个 run 的所有关键指标放一张表里，直接贴论文。

```bash
#!/bin/bash
# save as tools/compare_perf.sh
metrics="cycles instructions cache-references cache-misses LLC-loads LLC-load-misses context-switches cpu-migrations"

printf "%-24s %20s %20s %12s\n" "metric" "shared_short" "sharded_short" "sharded/shared"
printf "%-24s %20s %20s %12s\n" "----" "----" "----" "----"

for m in $metrics; do
  v_shared=$(perf report -i results/shared_short/shared_short.perf.data --stat 2>/dev/null \
    | awk -v m="$m" '$0 ~ "'"$m"'" {gsub(",",""); for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}')
  v_sharded=$(perf report -i results/sharded_short/sharded_short.perf.data --stat 2>/dev/null \
    | awk -v m="$m" '$0 ~ "'"$m"'" {gsub(",",""); for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/) {print $i; exit}}')
  if [[ -n "$v_shared" && -n "$v_sharded" && "$v_shared" -gt 0 ]]; then
    ratio=$(awk "BEGIN {printf \"%.2f\", $v_sharded / $v_shared}")
  else
    ratio="-"
  fi
  printf "%-24s %20s %20s %12s\n" "$m" "${v_shared:--}" "${v_sharded:--}" "$ratio"
done
```

跑：
```bash
bash tools/compare_perf.sh
```

**期望的论文表**（示例值）：

| metric           | shared_short | sharded_short | sharded/shared |
|------------------|--------------|---------------|----------------|
| cycles           | 3.8 × 10¹¹   | 2.6 × 10¹¹    | 0.68           |
| instructions     | 3.0 × 10¹¹   | 3.0 × 10¹¹    | 1.00           |
| IPC              | 0.79         | 1.15          | **1.46**       |
| cache-misses     | 8.2 × 10⁹    | 1.1 × 10⁹     | **0.13**       |
| LLC-load-misses  | 1.9 × 10⁸    | 2.5 × 10⁷     | **0.13**       |
| context-switches | 4.2 × 10⁶    | 3.1 × 10⁴     | **0.007**      |
| cpu-migrations   | 1.1 × 10⁵    | 5.0 × 10²     | **0.005**      |

> `sharded/shared` 列里的 ≪ 1 行就是 sharded 胜出的定量证据；
> IPC 那行的 > 1 说明 sharded 把 CPU "用实了"（没被内存/锁挡住）。

---

## 附：一键全量分析

把上面 6 张表一次性生成到 `results/analysis_report.txt`：

```bash
#!/bin/bash
# save as tools/analyze_all.sh
set -u
REPORT="results/analysis_report.txt"
exec > "$REPORT" 2>&1

echo "===== Table 1: per-event summary ====="
for d in shared_short sharded_short; do
  echo "--- $d ---"
  perf report -i results/$d/$d.perf.data --stat 2>/dev/null \
    | grep -E "cycles|instructions|cache|LLC|context-switches|cpu-migrations|futex|sched"
done

echo; echo "===== Table 3: futex enter counts ====="
for d in shared_short sharded_short; do
  c=$(perf script -i results/$d/$d.perf.data --ns 2>/dev/null | grep -c "sys_enter_futex")
  echo "$d : $c"
done

echo; echo "===== Table 4A: scheduler latency ====="
for d in shared_short sharded_short; do
  echo "--- $d ---"
  perf sched latency -i results/$d/$d.perf.data --sort max 2>/dev/null | head -30
done

echo; echo "===== Table 5: cpu-migrations total ====="
for d in shared_short sharded_short; do
  n=$(perf script -i results/$d/$d.perf.data 2>/dev/null | grep -c cpu-migrations)
  echo "$d : $n"
done

echo; echo "===== Table 6: side-by-side table ====="
bash tools/compare_perf.sh

echo; echo "Wrote $REPORT"
```

```bash
chmod +x tools/analyze_all.sh tools/compare_perf.sh
bash tools/analyze_all.sh
```

火焰图 (Table 2) 单独生成（SVG 不适合放 txt）：
```bash
for d in shared_short sharded_short; do
  perf script -i results/$d/$d.perf.data \
    | stackcollapse-perf.pl \
    | flamegraph.pl --title "$d" \
    > results/$d/cpu_flame.svg
done
```

---

## 常见坑

| 症状 | 原因 | 解决 |
|---|---|---|
| `perf sched latency` 说 `no events` | 录制时没开 `sched:*` tracepoint | 确认 YAML 的 `extraArgs` 含 `sched:sched_switch,sched:sched_wakeup` |
| `sys_enter_futex` 数为 0 | `perf_event_paranoid > 0` | `sudo sysctl -w kernel.perf_event_paranoid=-1` |
| 所有 Java 栈 `[unknown]` | 没有 `/tmp/perf-<pid>.map` 或 JVM 已退出后 map 被清 | 加 `-XX:+DumpPerfMapAtExit`，或开 `async-profiler` |
| 栈只有 1 层 | `--call-graph fp` 但没有 `-XX:+PreserveFramePointer` | 加该 JVM flag，或改 `callGraph: dwarf` |
| 火焰图底部是 `[unknown]` 一大片 | DWARF stack 被 8 KiB 截断 | 目前 PerfConfig 固定 `--call-graph dwarf`（默认 8K），Java 深栈可能超；改 `callGraph: fp` + PreserveFramePointer |
| `LOST X chunks` 在 `.perf.log` 里 | ring buffer 不够 | 加 `mmapPages`（必须 2 的幂） |
| `perf.data` > 2 GB | DWARF + 高频 + tracepoint 同开 | 加 `-z`（已默认），或 frequency 降到 99 |

时间对齐用 JFR anchor event（前置条件里的验证命令）。
````

