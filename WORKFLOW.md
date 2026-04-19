# System Workflow

## 整体流程图

```mermaid
flowchart TD
    CLI["CLI\n--config=benchmarks.yaml"]
    LOAD["BenchmarkConfigLoader\n加载并解析 YAML"]
    VALIDATE["RootConfig.validate()\n校验 global / workloads / runs"]
    CALIB{"global.baseIterations\n已配置?"}
    FIXED["直接使用配置值"]
    AUTO["WorkloadCalibrator\n自动校准 baseIterations\n（运行单任务计时循环）"]
    LOOP["遍历 runs[]"]
    EXECRUN["executeRun()\n初始化本次 Run"]

    CLI --> LOAD --> VALIDATE --> CALIB
    CALIB -- 是 --> FIXED --> LOOP
    CALIB -- 否 --> AUTO --> LOOP
    LOOP --> EXECRUN
```

---

## 单次 Run 执行流程

```mermaid
flowchart TD
    INIT["初始化\nTaskGenerator\nDispatcher\nJfrController"]
    MKDIR["创建输出目录\nresults/<runName>/"]

    WARMUP["Warmup Phase\nmeasurement=false\n不记录延迟"]
    WDONE["输出 Warmup 统计\n(提交数 / 耗时)"]

    JFRSTART["JfrController\njcmd JFR.start\n(beforeMeasurement)"]

    MEASURE["Measurement Phase\nmeasurement=true\n记录所有 Task"]
    MDONE["输出吞吐量 / 背压事件"]

    JFRSTOP["JfrController\njcmd JFR.stop\n(afterMeasurement)"]

    RECORD["LatencyRecorder\n离线计算 p50/p90/p95/p99\n(队列等待 / 执行 / 端到端)"]

    WRITE["写入结果文件\nresults/<runName>/summary.txt\nresults/<runName>/<runName>.jfr"]

    SHUTDOWN["Dispatcher.shutdown()\nawaitTermination(30s)"]

    INIT --> MKDIR --> WARMUP --> WDONE --> JFRSTART
    JFRSTART --> MEASURE --> MDONE --> JFRSTOP
    JFRSTOP --> RECORD --> WRITE --> SHUTDOWN
```

---

## runPhase 提交线程热路径

```mermaid
flowchart TD
    START(["开始 Phase\n创建 Semaphore(maxInflight)\n预分配 tasks 列表"])

    COND{"taskLimit > 0 ?\n(固定任务数)\n否则按时间截止"}

    TRYACQ["Semaphore.tryAcquire()\n(non-blocking)"]
    BACKP["backpressure++\nSemaphore.acquire()\n(blocking — 背压)"]
    ACQUIRE["获得 permit"]

    GEN["TaskGenerator.nextTask(taskId)\n▸ 确定 TaskType\n  单一: 直接返回 singleType\n  混合: deterministic hash draw\n▸ iterations = base × multiplier\n▸ taskSeed = seed + taskId\n▸ new Task(id, type, iters, submitNanos, seed, onComplete)"]

    SUBMIT["Dispatcher.submit(task)\n▸ shared → LinkedBlockingQueue\n▸ sharded → queues[hash(taskId) % N]"]

    SAVE["tasks.add(task)\ntaskId++\nsubmitted++"]

    DRAIN["Semaphore.acquire(maxInflight)\n等待所有 in-flight 任务完成\nSemaphore.release(maxInflight)"]

    RESULT(["返回 PhaseResult\n(tasks, submitted,\nelapsed, backpressure, nextTaskId)"])

    START --> COND
    COND -- 未完成 --> TRYACQ
    TRYACQ -- 失败 --> BACKP --> ACQUIRE
    TRYACQ -- 成功 --> ACQUIRE
    ACQUIRE --> GEN --> SUBMIT --> SAVE --> COND
    COND -- 完成 --> DRAIN --> RESULT
```

---

## Worker 线程执行路径

```mermaid
flowchart TD
    DEQUEUE["从队列取出 Task\n▸ SharedWorker: ThreadPoolExecutor 调度\n▸ ShardedWorker: localQueue.take()"]

    RUN["Task.run()"]
    TSTART["startNanos = System.nanoTime()"]
    EXEC["执行工作负载\nworkload != null ?\n→ workload.execute()\n: CpuBoundWorkload.execute(seed, iterations)\n（无对象分配热路径）"]
    TFINISH["finishNanos = System.nanoTime()"]
    CB["onComplete.run()\n→ permits.release()\n通知提交线程可继续"]

    COUNTER["processedCount++\nisMeasurement? → measurementProcessedCount++"]

    DEQUEUE --> RUN --> TSTART --> EXEC --> TFINISH --> CB --> COUNTER --> DEQUEUE
```

---

## TaskType 与负载分配

```mermaid
flowchart LR
    GEN["TaskGenerator"]

    subgraph SINGLE["单一模式 (kind: single)"]
        S_T["固定 TaskType\ne.g. SHORT"]
    end

    subgraph MIX["混合模式 (kind: mix)"]
        HASH["deterministic hash\n(无分配)\ndraw = hash(seed ^ taskId) % 100"]
        S["draw < short%\n→ SHORT\n× 1× iterations"]
        M["draw < short%+medium%\n→ MEDIUM\n× 10× iterations"]
        L["其余\n→ LONG\n× 100× iterations"]
        HASH --> S
        HASH --> M
        HASH --> L
    end

    GEN --> SINGLE
    GEN --> MIX
```

---

## JFR 命令行控制

```mermaid
sequenceDiagram
    participant BM as BenchmarkMain
    participant JFR as JfrController
    participant JCMD as jcmd (CLI)
    participant JVM as 当前 JVM 进程

    BM->>JFR: create(profiling, runName, runDir)
    Note over JFR: 展开模板变量<br>${runName} / ${settings} / ${outputFile}

    BM->>JFR: startBeforeMeasurement()
    JFR->>JCMD: jcmd <pid> JFR.start name=... settings=... filename=...
    JCMD->>JVM: 启动 JFR 录制

    Note over BM: 执行 Measurement Phase

    BM->>JFR: stopAfterMeasurement()
    JFR->>JCMD: jcmd <pid> JFR.stop name=... filename=...
    JCMD->>JVM: 停止录制并写入 .jfr 文件

    BM->>JFR: close()
    Note over JFR: 若未 dump 则补一次 stop
```

---

## 输出文件结构

```
results/
└── <runName>/
    ├── summary.txt        # 文字摘要：配置、吞吐量、p50/p90/p95/p99
    └── <runName>.jfr      # JFR 录制文件（profiling.enabled=true 时生成）
```

`summary.txt` 内容：

```
runName=shared_short
mode=shared
workload=short_only
submitted=100000
durationSeconds=10.012
throughput=9988.0 tasks/s
backpressureEvents=42

Recorded tasks: 100000

Metric             p50        p90        p95        p99
------------------------------------------------------------
Queue wait      0.004 ms   0.010 ms   0.016 ms   0.023 ms
Execution       0.006 ms   0.008 ms   0.008 ms   0.008 ms
End-to-end      0.011 ms   0.017 ms   0.024 ms   0.032 ms
```

---

## 组件依赖关系

```mermaid
graph TD
    BM["BenchmarkMain"]
    CFG["BenchmarkConfigLoader"]
    ROOT["RootConfig"]
    GLOB["GlobalConfig"]
    WCFG["WorkloadConfig"]
    PCFG["ProfilingConfig"]
    RCFG["RunConfig"]
    TGEN["TaskGenerator"]
    TASK["Task"]
    WLOAD["CpuBoundWorkload\n(static execute)"]
    DISP["Dispatcher\n(interface)"]
    SH["SharedOnlyDispatcher\n→ SharedExecutor"]
    SD["ShardedOnlyDispatcher\n→ ShardedExecutor / ShardedWorker"]
    JFR["JfrController\n(jcmd CLI)"]
    LAT["LatencyRecorder"]
    CAL["WorkloadCalibrator"]

    BM --> CFG --> ROOT
    ROOT --> GLOB
    ROOT --> WCFG
    ROOT --> PCFG
    ROOT --> RCFG

    BM --> CAL
    BM --> TGEN
    BM --> DISP
    BM --> JFR
    BM --> LAT

    TGEN --> TASK
    TASK --> WLOAD

    DISP --> SH
    DISP --> SD
```

