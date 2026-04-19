# System Architecture

## High-Level Architecture

```mermaid
flowchart TD
    CLI(["fa:fa-terminal CLI\n--config=benchmarks.yaml"])

    subgraph CONFIG["Configuration Layer"]
        LOADER["BenchmarkConfigLoader\nSnakeYAML parser"]
        ROOT["RootConfig"]
        GLOBAL["GlobalConfig\nworkerCount / maxInflight\nseed / targetTaskNanos\nwarmupSeconds / measurementSeconds\ntaskCount"]
        WORKLOADS["WorkloadConfig\nkind: single | mix\ndistribution / generation"]
        PROFILING["ProfilingConfig\ncontrol: cli\nstartCommand / stopCommand\nfilename template"]
        RUNS["RunConfig[]\nname / mode / workload"]
    end

    subgraph CALIBRATION["Calibration Layer"]
        CALIB["WorkloadCalibrator\nAuto-calibrate baseIterations\nto match targetTaskNanos"]
    end

    subgraph EXECUTION["Execution Layer"]
        TGEN["TaskGenerator\nAllocation-free hot path\nDeterministic type sampling"]
        PHASE["runPhase()\nOpen-loop Semaphore\nBackpressure control"]
        DISPATCHER["Dispatcher\ninterface"]
        SHARED["SharedOnlyDispatcher\n→ SharedExecutor\n1 shared LinkedBlockingQueue\nThreadPoolExecutor"]
        SHARDED["ShardedOnlyDispatcher\n→ ShardedExecutor\nN per-worker queues\nHash routing"]
    end

    subgraph WORKER["Worker Layer"]
        SW["SharedWorker\nThreadPoolExecutor threads"]
        SDW["ShardedWorker\nDedicated threads\nlocalQueue.take()"]
        TASK["Task.run()\nrecord startNanos\nCpuBoundWorkload.execute(seed, iters)\nrecord finishNanos\nonComplete → release permit"]
    end

    subgraph JFR["JFR Profiling Layer"]
        JFRC["JfrController"]
        JCMD["jcmd CLI\nJFR.start / JFR.stop"]
        JVM["JVM Flight Recorder"]
    end

    subgraph OUTPUT["Output Layer"]
        LAT["LatencyRecorder\np50 / p90 / p95 / p99\nqueue wait / execution\nend-to-end"]
        SUMM["results/<runName>/summary.txt"]
        JFRFILE["results/<runName>/<runName>.jfr"]
    end

    CLI --> LOADER --> ROOT
    ROOT --> GLOBAL & WORKLOADS & PROFILING & RUNS
    GLOBAL --> CALIB
    RUNS --> TGEN
    WORKLOADS --> TGEN
    CALIB --> TGEN

    TGEN --> PHASE
    PHASE --> DISPATCHER
    DISPATCHER --> SHARED & SHARDED

    SHARED --> SW --> TASK
    SHARDED --> SDW --> TASK

    TASK -- "onComplete()\nreleases permit" --> PHASE

    PROFILING --> JFRC --> JCMD --> JVM --> JFRFILE
    PHASE --> LAT --> SUMM
```

---

## Component Responsibilities

```mermaid
flowchart LR
    subgraph ENTRY["Entry Point"]
        BM["BenchmarkMain\n① parse --config=\n② load + validate YAML\n③ calibrate iterations\n④ loop over runs\n⑤ print results"]
    end

    subgraph CONFIG["Config Model"]
        RC["RootConfig"]
        GC["GlobalConfig"]
        WC["WorkloadConfig"]
        PC["ProfilingConfig"]
        RUN["RunConfig"]
        RC --> GC & WC & PC & RUN
    end

    subgraph GEN["Task Generation"]
        TG["TaskGenerator\n• Pre-resolved type config\n• Allocation-free hash draw\n• No CpuBoundWorkload object\n  per task"]
        TT["TaskType\nSHORT  ×1\nMEDIUM ×10\nLONG   ×100"]
        TG --> TT
    end

    subgraph EXEC["Execution"]
        D["Dispatcher\n(interface)"]
        SE["SharedExecutor\nTPE + LinkedBlockingQueue"]
        SDE["ShardedExecutor\nN queues + N ShardedWorkers\nhash(taskId) % N routing"]
        D --> SE & SDE
    end

    subgraph TASK_EXEC["Task Execution (Worker Threads)"]
        T["Task\n• taskId / type / iterations\n• submitNanos (set on creation)\n• startNanos / finishNanos\n  (set in run())"]
        CBW["CpuBoundWorkload\nstatic execute(seed, iters)\nno allocation on hot path"]
        T --> CBW
    end

    subgraph RECORD["Recording & Output"]
        LR["LatencyRecorder\nLongBuffer — no boxing\np50/p90/p95/p99"]
        OUT["results/<runName>/\n  summary.txt\n  <runName>.jfr"]
        LR --> OUT
    end

    subgraph JFR["JFR Control"]
        JFRC["JfrController\nexpand template vars\n${runName}\n${settings}\n${outputFile}"]
        JCMD["jcmd <pid>\nJFR.start / JFR.stop"]
        JFRC --> JCMD
    end

    BM --> CONFIG
    BM --> GEN
    BM --> EXEC
    BM --> TASK_EXEC
    BM --> RECORD
    BM --> JFR
```

---

## Execution Lifecycle (Sequence)

```mermaid
sequenceDiagram
    participant Main as BenchmarkMain
    participant Loader as BenchmarkConfigLoader
    participant Calib as WorkloadCalibrator
    participant Gen as TaskGenerator
    participant Disp as Dispatcher
    participant JFR as JfrController
    participant Worker as Worker Threads
    participant Rec as LatencyRecorder
    participant FS as File System

    Main->>Loader: load(config path)
    Loader-->>Main: RootConfig (validated)

    alt baseIterations not set in YAML
        Main->>Calib: calibrateIterations(targetNanos, seed)
        Calib-->>Main: baseIterations
    end

    loop for each RunConfig
        Main->>Gen: new TaskGenerator(workload, baseIterations, seed)
        Main->>Disp: createDispatcher(mode, workerCount)
        Main->>JFR: JfrController.create(profiling, runName, runDir)
        Main->>FS: createDirectories(results/<runName>/)

        Note over Main,Worker: ── Warmup Phase ──
        Main->>Gen: nextTask(taskId, measurement=false, releasePermit)
        Gen-->>Main: Task (allocation-free)
        Main->>Disp: submit(task)
        Disp->>Worker: enqueue task
        Worker->>Worker: Task.run() → CpuBoundWorkload.execute()
        Worker->>Main: onComplete() → Semaphore.release()

        Note over Main,JFR: ── JFR Start ──
        Main->>JFR: startBeforeMeasurement()
        JFR->>JFR: jcmd <pid> JFR.start name=... settings=... filename=...

        Note over Main,Worker: ── Measurement Phase ──
        Main->>Gen: nextTask(taskId, measurement=true, releasePermit)
        Gen-->>Main: Task
        Main->>Disp: submit(task)
        Disp->>Worker: enqueue task
        Worker->>Worker: Task.run() → record timing
        Worker->>Main: onComplete() → Semaphore.release()

        Note over Main,JFR: ── JFR Stop ──
        Main->>JFR: stopAfterMeasurement()
        JFR->>JFR: jcmd <pid> JFR.stop name=... filename=...

        Main->>Rec: record(task) for each measurement task
        Rec-->>Main: p50/p90/p95/p99

        Main->>FS: write summary.txt
        Main->>Disp: shutdown() + awaitTermination(30s)
    end
```

---

## Data Flow Through the Hot Path

```mermaid
flowchart LR
    subgraph SUBMIT["Submit Thread (Main)"]
        direction TB
        S1["Semaphore.tryAcquire()"]
        S2["TaskGenerator.nextTask(taskId)\n→ taskType via hash/fixed\n→ iterations = base × multiplier\n→ new Task(id, type, iters,\n  submitNanos, workloadSeed, cb)"]
        S3["Dispatcher.submit(task)\n→ queue.offer(task)"]
        S4["tasks.add(task)\ntaskId++  submitted++"]
        S1 --> S2 --> S3 --> S4
    end

    subgraph QUEUE["Queue (no-alloc enqueue)"]
        Q["LinkedBlockingQueue node\n(JDK internal)"]
    end

    subgraph WORKER["Worker Thread"]
        direction TB
        W1["queue.take() / TPE dequeue"]
        W2["startNanos = nanoTime()"]
        W3["CpuBoundWorkload\n.execute(seed, iters)"]
        W4["finishNanos = nanoTime()"]
        W5["onComplete()\n→ Semaphore.release()"]
        W1 --> W2 --> W3 --> W4 --> W5
    end

    subgraph DRAIN["After Phase"]
        D1["Semaphore.acquire(maxInflight)\nDrain all in-flight tasks"]
        D2["LatencyRecorder.record(task)\nqueueWait = startNanos - submitNanos\nexecution = finishNanos - startNanos\nendToEnd = finishNanos - submitNanos"]
        D1 --> D2
    end

    SUBMIT --> QUEUE --> WORKER
    WORKER -- "permit released" --> SUBMIT
    WORKER --> DRAIN
```

---

## Queue Topology Comparison

```mermaid
flowchart TD
    subgraph SHARED_MODE["mode: shared — SharedExecutor\n1 queue shared by all threads"]
        direction LR
        P_S["Producer\n(Main Thread)"]
        Q_S["1 × LinkedBlockingQueue\nshared — unbounded\nall workers compete on\nthe same queue lock"]
        W1_S["SharedWorker-0\n(thread)"]
        W2_S["SharedWorker-1\n(thread)"]
        W3_S["SharedWorker-N\n(thread)"]
        P_S -->|"offer(task)"| Q_S
        Q_S -->|"take()"| W1_S
        Q_S -->|"take()"| W2_S
        Q_S -->|"take()"| W3_S
    end

    subgraph SHARDED_MODE["mode: sharded — ShardedExecutor\n1 queue per thread  ·  no cross-queue contention"]
        direction LR
        P_SD["Producer\n(Main Thread)"]
        ROUTE["hash(taskId) % N\nrouting — O(1)"]

        subgraph SH0["Shard 0  (1 queue · 1 thread)"]
            Q1["Queue-0\nLinkedBlockingQueue"]
            W1_SD["ShardedWorker-0\n(dedicated thread)"]
            Q1 -->|"take()"| W1_SD
        end

        subgraph SH1["Shard 1  (1 queue · 1 thread)"]
            Q2["Queue-1\nLinkedBlockingQueue"]
            W2_SD["ShardedWorker-1\n(dedicated thread)"]
            Q2 -->|"take()"| W2_SD
        end

        subgraph SHN["Shard N  (1 queue · 1 thread)"]
            QN["Queue-N\nLinkedBlockingQueue"]
            WN_SD["ShardedWorker-N\n(dedicated thread)"]
            QN -->|"take()"| WN_SD
        end

        P_SD -->|"offer(task)"| ROUTE
        ROUTE --> Q1
        ROUTE --> Q2
        ROUTE --> QN
    end

    NOTE_S["✦ Work stealing: any idle worker\n  picks the next available task"]
    NOTE_SD["✦ No stealing: each worker owns\n  its queue exclusively"]

    SHARED_MODE --- NOTE_S
    SHARDED_MODE --- NOTE_SD
```

---

## YAML Config Structure

```mermaid
flowchart TD
    YAML["benchmarks.yaml"]

    subgraph G["global"]
        G1["workerCount: 16"]
        G2["maxInflight: 32"]
        G3["seed: 3735928559"]
        G4["targetTaskNanos: 100000"]
        G5["warmupSeconds: 3"]
        G6["measurementSeconds: 10"]
        G7["taskCount: 0\n(0 = time-based)"]
        G8["baseIterations: (optional)\nskips auto-calibration"]
    end

    subgraph W["workloads"]
        W1["short_only\n  kind: single\n  type: short"]
        W2["mixed_60_30_10\n  kind: mix\n  distribution:\n    short: 60\n    medium: 30\n    long: 10\n  generation: shuffled"]
    end

    subgraph P["profiling"]
        P1["enabled: true"]
        P2["control: cli"]
        P3["settings: profile"]
        P4["start: beforeMeasurement"]
        P5["stop: afterMeasurement"]
        P6["filename: \${runName}.jfr"]
        P7["startCommand: JFR.start\n  name=\${runName}\n  settings=\${settings}\n  filename=\${outputFile}"]
        P8["stopCommand: JFR.stop\n  name=\${runName}\n  filename=\${outputFile}"]
    end

    subgraph R["runs"]
        R1["- name: shared_short\n  mode: shared\n  workload: short_only"]
        R2["- name: sharded_mix\n  mode: sharded\n  workload: mixed_60_30_10"]
    end

    YAML --> G & W & P & R
```

