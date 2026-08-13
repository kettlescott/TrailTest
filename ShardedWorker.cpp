/******************************************************************************
 *
 * Copyright (C) 2026 LexisNexis Risk Solutions
 *
 * Description:
 *   ShardWorker implementation — single-threaded worker that owns a set of
 *   logical shards and processes all operations for entities in those shards.
 *
 *   Phase 2: Entity queue management, background ops, coalescing.
 *   Phase 3: Per-entity queue depth cap, condition_variable wakeup, coalescing stats.
 *
 ******************************************************************************/

#include "ShardWorker.h"
#include "ShardWorkItemPool.h"
#include "FetchThreadPool.h"
#include "ShardWriteThread.h"
#include "ThreatMETRIXCommon.h"
#include "StorageEngine_PerformanceTimerConfigurator.h"
#include <Utility/AffinityManager.h>

#include <Synergy/TmMetrics.h>
#include <immintrin.h> // _mm_pause

#ifdef __linux__
#include <sched.h>     // sched_setaffinity
#include <pthread.h>
#endif

const size_t RESERVE_ENTITIY_QUEUE_SIZE = 4096; // Pre-reserve space in entity queues to avoid mid-operation reallocations (perf optimization)

ShardWorker::ShardWorker(uint32_t workerIndex, uint32_t numWorkers, uint32_t numShardQueues, uint32_t queueDepthPerShard, uint32_t entityQueueDepth)
    : m_workerIndex(workerIndex)
    , m_numWorkers(numWorkers)
    , m_entityQueueDepth(entityQueueDepth)
    , m_numShardQueues(numShardQueues > 0 ? numShardQueues : 1)
    , m_completionNotifyQueue(queueDepthPerShard)
{
    m_shardQueues.reserve(m_numShardQueues);
    for (uint32_t i = 0; i < m_numShardQueues; ++i)
        m_shardQueues.push_back(std::make_unique<Queue>(queueDepthPerShard));

    m_entityQueues.reserve(RESERVE_ENTITIY_QUEUE_SIZE);
}

ShardWorker::~ShardWorker()
{
    Stop();

    // Uninitialize worker engines (flush caches, etc.)
    TH_LOG(DLT_INFORMATION, DLM_LEVEL_1, "ShardWorker[%u] stopped. Unitializing %zu worker engines",
            m_workerIndex, m_workerEngines.size());
    for (const auto& [name, engine] : m_workerEngines)
    {
        if (engine && engine->IsInitialized())
        {
            if (IsClonedFromMasterEngine(name, engine))
            {
                // The worker engine has been cloned from the master engine,
                // so it's safe to uninitialize without affecting the master (and other workers).
                engine->UnInitialize();
            }
            else
            {
                // The worker engine is a pointer to the master engine, don't uninitialize it
                // It is probably a mocked engine used for testing, and the test is responsible for its lifecycle
                TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                    "ShardWorker[%u]: master engine for '%s' is the same instance as the worker engine — probably a test mock — skipping UnInitialize()",
                    m_workerIndex, name.c_str());
            }
        }
    }
}

void ShardWorker::Start(int32_t coreId)
{
    if (m_running.exchange(true))
        return; // already running

    m_thread = std::jthread(&ShardWorker::Run, this, coreId);

    TH_LOG(DLT_INFORMATION, DLM_LEVEL_1, "ShardWorker[%u] started (coreAffinity=%d)",
           m_workerIndex, coreId);
}

void ShardWorker::Stop()
{
    if (!m_running.exchange(false))
        return; // already stopped

    Wake(); // Wake the worker from condvar sleep so it exits promptly

    if (m_thread.joinable())
        m_thread.join();

    TH_LOG(DLT_INFORMATION, DLM_LEVEL_1, "ShardWorker[%u] stopped (processed=%lu, dropped=%lu, coalesced=%lu, stale=%lu, entities=%zu)",
           m_workerIndex, m_processedCount.load(), m_droppedCount.load(),
           m_coalescedCount.load(), m_staleCount.load(), m_entityQueues.size());
}

void ShardWorker::SetCoreAffinity(int32_t coreId)
{
#ifdef __linux__
    if (coreId < 0)
    {
        // If a specific coreId is not requested, use the AffinityManager live thread CPUs if configured, otherwise all CPUs.
        AffinityManager::GetInstance()->UseLivethreadCPUs();
        TH_LOG(DLT_INFORMATION, DLM_LEVEL_1, "ShardWorker[%u] uses AffinityManager LiveThreadCPUs", m_workerIndex);
        return;
    }

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(coreId, &cpuset);

    int rc = pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
    if (rc != 0)
    {
        TH_LOG(DLT_WARNING, DLM_LEVEL_1,
               "ShardWorker[%u]: failed to set CPU affinity to core %d (errno=%d)",
               m_workerIndex, coreId, rc);
    }
    else
    {
        TH_LOG(DLT_INFORMATION, DLM_LEVEL_1,
               "ShardWorker[%u]: pinned to CPU core %d", m_workerIndex, coreId);
    }
#else
    (void)coreId; // Core affinity only supported on Linux
#endif
}

void ShardWorker::Wake()
{
    // Fast path: if flag is already set, the worker will see it
    // before sleeping. No need to lock or notify.
    if (m_wakeFlag.load(std::memory_order_acquire))
        return;

    // Must lock the mutex to prevent lost wakeup race:
    // Without the lock, a notify_one can fire between the predicate
    // check and entering wait_for in WaitForWork(), causing the worker
    // to sleep for up to 200ms even though work is available.
    {
        std::scoped_lock lk(m_wakeMutex);
        m_wakeFlag.store(true, std::memory_order_release);
    }
    m_wakeCv.notify_one();
}

void ShardWorker::WaitForWork()
{
    // Double-check: items may have arrived between the last try_pop and now
    for (const auto& q : m_shardQueues)
    {
        WorkItemPtr peek = nullptr;
        if (q->try_pop(peek))
        {
            if (peek != nullptr)
            {
                ProcessWorkItem(peek);
                if (peek != nullptr)
                    CompleteAndMaybeDelete(peek);
            }
            return;
        }
    }

    // Check for background ops before sleeping
    CheckAndRunBackgroundOps();

    // Sleep until woken by a Dispatch() call or timeout for periodic background ops.
    // Background ops period is seconds, so 200ms is more than frequent enough
    // while keeping idle CPU near zero (16 workers × 5 wakeups/s = 80 wakeups/s).
    {
        std::unique_lock lk(m_wakeMutex);
        m_wakeCv.wait_for(lk, std::chrono::milliseconds(200),
            [this]{ return m_wakeFlag.load(std::memory_order_acquire); });
        m_wakeFlag.store(false, std::memory_order_relaxed);
    }
}

void ShardWorker::Run(int32_t coreId)
{
    SetCoreAffinity(coreId);
    InitializeWorkerEngines();

    uint32_t idleSpins = 0;

    auto now = Clock::now();
    m_timing.Reset(now);

    while (m_running.load(std::memory_order_relaxed))
    {
        // Phase 1: Drain shard queues and completion notifications into per-entity queues
        auto drainStart = Clock::now();
        bool anyDrained = DrainShardQueuesToEntityQueues();
        auto drainEnd = Clock::now();
        m_timing.drainUs += std::chrono::duration_cast<std::chrono::microseconds>(drainEnd - drainStart).count();

        if (anyDrained || !m_readyEntities.empty())
        {
            idleSpins = 0;
            auto processStart = Clock::now();
            ProcessActiveEntities();
            auto processEnd = Clock::now();
            m_timing.processingUs += std::chrono::duration_cast<std::chrono::microseconds>(processEnd - processStart).count();
            m_timing.batchCount++;

            // Run background ops periodically even under sustained load,
            // otherwise cache maintenance (memory cleanup) never runs.
            CheckAndRunBackgroundOps();
        }
        else
        {
            m_timing.emptyPolls++;
            // Adaptive backoff: spin → yield → condvar sleep
            ++idleSpins;
            if (idleSpins < SPIN_COUNT)
            {
                _mm_pause();
            }
            else if (idleSpins < SPIN_COUNT + YIELD_COUNT)
            {
                std::this_thread::yield();
            }
            else
            {
                auto waitStart = Clock::now();
                WaitForWork();
                auto waitEnd = Clock::now();
                m_timing.idleWaitUs += std::chrono::duration_cast<std::chrono::microseconds>(waitEnd - waitStart).count();
                idleSpins = 0;
            }
        }

        // Report timing stats every 10 seconds
        auto reportNow = Clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(reportNow - m_timing.lastReport);
        if (elapsed.count() >= 10)
            ReportTimingStats();
    }

    // Drain remaining items after m_running goes false
    DrainShardQueuesToEntityQueues();
    ProcessActiveEntities();
}

// Classify a work item's op type for per-entity admission control
EntityQueue::OpCategory ShardWorker::ClassifyOp(const ShardWorkItem& item) const
{
    return std::visit(absl::Overload{
        [](const GetRecordOp&)            -> EntityQueue::OpCategory { return EntityQueue::OpCategory::Get; },
        [](const GetSmartIdIrSnapshotOp&) -> EntityQueue::OpCategory { return EntityQueue::OpCategory::Get; },
        [](const UpdateRecordOp&)         -> EntityQueue::OpCategory { return EntityQueue::OpCategory::Update; },
        [](const ExtendTTLOp&)            -> EntityQueue::OpCategory { return EntityQueue::OpCategory::ExtendTTL; },
        [](const CacheHintOp&)            -> EntityQueue::OpCategory { return EntityQueue::OpCategory::Other; }
    }, item);
}

bool ShardWorker::DrainShardQueuesToEntityQueues()
{
    bool anyDrained = false;
    ASSERT(m_numShardQueues > 0);
    if (m_numShardQueues == 0)
        return anyDrained;

    // Pop items from shard queues (round-robin for fairness)
    {
        bool popped = true;
        while (popped)
        {
            popped = false;
            for (uint32_t i = 0; i < m_numShardQueues; ++i)
            {
                uint32_t qIdx = (m_drainRoundRobin + i) % m_numShardQueues;
                WorkItemPtr item = nullptr;
                if (m_shardQueues[qIdx]->try_pop(item))
                {
                    popped = true;
                    if (item == nullptr) continue;
                    anyDrained = true;

                    // CacheHint: process inline (fire-and-forget, near-instant)
                    if (std::holds_alternative<CacheHintOp>(*item))
                    {
                        ProcessCacheHint(item);
                        if (item != nullptr)
                            CompleteAndMaybeDelete(item);
                        continue;
                    }

                    // Skip stale read ops — handler already timed out
                    if (isStaleReadOp(*item))
                    {
                        m_staleCount.fetch_add(1, std::memory_order_relaxed);
                        CompleteAndMaybeDelete(item);
                        continue;
                    }

                    // Entity-bound op: route to per-entity queue with admission control
                    std::string key = MakeEntityKey(getEntityTypeName(*item), getOrgId(*item),
                                                    getEntityValue(*item), getIsGlobalScope(*item));
                    EntityQueue& eq = GetOrCreateEntityQueue(key);
                    auto cat = ClassifyOp(*item);

                    if (!eq.tryEnqueue(item, cat))
                    {
                        // Per-type or overall cap exceeded — reject immediately
                        std::visit(absl::Overload{
                            [](GetRecordOp& op)            { op.result.readResult = SeReadResult::FAIL; },
                            [](GetSmartIdIrSnapshotOp& op) { op.result.readResult = SeReadResult::FAIL; },
                            [](UpdateRecordOp& op)         { op.result.success = false; op.result.cacheRecordUpdateLimitReached = true; },
                            [](ExtendTTLOp& op)            { op.result.success = false; },
                            [](CacheHintOp&)               {}
                        }, *item);

                        CompleteAndMaybeDelete(item);
                        m_droppedCount.fetch_add(1, std::memory_order_relaxed);

                        // Track per-type rejections
                        switch (cat)
                        {
                        case EntityQueue::OpCategory::Update:       ++m_rejectedUpdates;    break;
                        case EntityQueue::OpCategory::Get:          ++m_rejectedGets;       break;
                        case EntityQueue::OpCategory::ExtendTTL:    ++m_rejectedExtendTTLs; break;
                        default: break;
                        }

                        if (m_entityQueueDropWarning.CanPerformAction())
                        {
                            const char* capType = "overall";
                            if (cat == EntityQueue::OpCategory::Update && eq.isUpdateFull())
                                capType = "update";
                            else if (cat == EntityQueue::OpCategory::Get && eq.isGetFull())
                                capType = "get";
                            else if (cat == EntityQueue::OpCategory::ExtendTTL && eq.isExtendTTLFull())
                                capType = "extendTTL";
                            TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                                   "ShardWorker[%u]: hot entity rejected %s op "
                                   "(pending=%zu/%u upd=%u/%u get=%u/%u ttl=%u/%u entity=%s)",
                                   m_workerIndex, capType,
                                   eq.pendingOps.size(), eq.maxPendingOps,
                                   eq.pendingUpdateCount, eq.maxPendingUpdates,
                                   eq.pendingGetCount, eq.maxPendingGets,
                                   eq.pendingExtendTTLCount, eq.maxPendingExtendTTLs,
                                   key.c_str());
                        }
                        continue;
                    }

                    // Accepted — enqueue entity for runnable processing.
                    MarkEntityReady(key, eq);
                }
            }
        }
        m_drainRoundRobin = (m_drainRoundRobin + 1) % m_numShardQueues;
    }

    // Collect completed fetches via the notification queue and fallback scan
    if (DrainCompletionNotifications())
        anyDrained = true;

    return anyDrained;
}

bool ShardWorker::DrainCompletionNotifications()
{
    bool anyDrained = false;

    // Collect completed fetches via the notification queue
    EntityQueue* completedEq = nullptr;
    while (m_completionNotifyQueue.try_pop(completedEq))
    {
        if (completedEq)
        {
            if (completedEq->fetchCompletionReady.load(std::memory_order_acquire))
            {
                completedEq->fetchCompletionReady.store(false, std::memory_order_relaxed);
                anyDrained = true;
                ProcessFetchComplete(completedEq->fetchCompletion);
                m_processedCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    // Fallback: if the notification queue was full, a pool thread set this
    // flag instead. Do a full scan to find any orphaned completions.
    if (m_hasPendingCompletions.exchange(false, std::memory_order_acquire))
    {
        for (auto& [key, eq] : m_entityQueues)
        {
            if (eq.fetchCompletionReady.load(std::memory_order_acquire))
            {
                eq.fetchCompletionReady.store(false, std::memory_order_relaxed);
                anyDrained = true;
                ProcessFetchComplete(eq.fetchCompletion);
                m_processedCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    return anyDrained;
}

void ShardWorker::ProcessActiveEntities()
{
    // Round-robin across ready entities, processing up to ENTITY_OPS_BUDGET
    // items per entity turn.
    constexpr size_t COMPLETION_DRAIN_INTERVAL = 64;
    size_t entitiesProcessedSinceLastDrain = 0;
    while (!m_readyEntities.empty())
    {
        std::string entityKey = std::move(m_readyEntities.front());
        m_readyEntities.pop_front();

        auto it = m_entityQueues.find(entityKey);
        if (it == m_entityQueues.end())
            continue;

        EntityQueue& eq = it->second;
        eq.inReadyQueue = false;

        if (!eq.hasPendingOps() || eq.fetchInFlight)
            continue;

        // Dequeue up to ENTITY_OPS_BUDGET items for this entity turn
        uint32_t processed = 0;
        while (processed < ENTITY_OPS_BUDGET && eq.hasPendingOps() && !eq.fetchInFlight)
        {
            WorkItemPtr item = eq.dequeue();
            if (item == nullptr) break;

            // Track queue wait time
            uint64_t qwUs = 0;
            auto computeWait = [&qwUs](const auto& enqueueTime) {
                qwUs = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - enqueueTime).count();
            };
            std::visit(absl::Overload{
                [&computeWait](const GetRecordOp& op)            { computeWait(op.enqueueTime); },
                [&computeWait](const GetSmartIdIrSnapshotOp& op) { computeWait(op.enqueueTime); },
                [&computeWait](const UpdateRecordOp& op)         { computeWait(op.enqueueTime); },
                [&computeWait](const ExtendTTLOp& op)            { computeWait(op.enqueueTime); },
                [](const CacheHintOp&)                           {}
            }, *item);
            m_timing.queueWaitUs += qwUs;
            if (qwUs > m_timing.queueWaitMaxUs) m_timing.queueWaitMaxUs = qwUs;

            ProcessWorkItem(item, &entityKey, &eq);
            if (item != nullptr)
                CompleteAndMaybeDelete(item);

            processed++;
        }

        // Re-queue only runnable entities (pending ops and no fetch in flight).
        MarkEntityReady(entityKey, eq);

        // Periodically drain fetch completions to prevent the notification
        // queue from overflowing (which triggers an expensive full scan).
        if (++entitiesProcessedSinceLastDrain >= COMPLETION_DRAIN_INTERVAL)
        {
            entitiesProcessedSinceLastDrain = 0;
            DrainCompletionNotifications();
        }
    }
}

// ---------------------------------------------------------------------------
// Extracted helpers — purely for readability, no behavior changes
// ---------------------------------------------------------------------------

void ShardWorker::ReportTimingStats()
{
    auto reportNow = Clock::now();
    uint64_t wallUs = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(reportNow - m_timing.lastReport).count());
    if (wallUs > 0)
    {
        uint64_t ops = m_processedCount.load(std::memory_order_relaxed);
        uint64_t dropped = m_droppedCount.load(std::memory_order_relaxed);
        uint64_t procPct = m_timing.processingUs * 100 / wallUs;
        uint64_t idlePct = m_timing.idleWaitUs * 100 / wallUs;
        uint64_t otherUs = wallUs > (m_timing.processingUs + m_timing.idleWaitUs + m_timing.drainUs)
            ? wallUs - m_timing.processingUs - m_timing.idleWaitUs - m_timing.drainUs
            : 0;
        uint64_t totalOps = m_timing.getCount + m_timing.updateCount;

        // Per-entity update rate stats — reset interval counters
        uint32_t activeEntities = 0;
        for (auto& [k, eq] : m_entityQueues)
        {
            if (eq.intervalUpdateCount > 0)
            {
                activeEntities++;
                eq.intervalUpdateCount = 0;
            }
        }

        // Publish snapshot for aggregate reporting (read by worker 0)
        m_snapshot.procPct = procPct;
        m_snapshot.ops = ops;
        m_snapshot.dropped = dropped;
        m_snapshot.getCount = m_timing.getCount;
        m_snapshot.updateCount = m_timing.updateCount;
        m_snapshot.cacheHits = m_timing.cacheHits;
        m_snapshot.cacheMisses = m_timing.cacheMisses;
        m_snapshot.fetchQueued = m_timing.fetchQueued;
        m_snapshot.fetchDropped = m_timing.fetchDropped;
        m_snapshot.fetchRequests = m_timing.fetchRequests;
        m_snapshot.fetchRequestsFound = m_timing.fetchRequestsFound;
        m_snapshot.fetchRequestsNotFound = m_timing.fetchRequestsNotFound;
        m_snapshot.cacheUpdAvgUs = m_timing.updateCount > 0 ? m_timing.cacheUpdateUs / m_timing.updateCount : 0;
        m_snapshot.cacheGetAvgUs = m_timing.getCount > 0 ? m_timing.cacheGetUs / m_timing.getCount : 0;
        m_snapshot.cacheUpdMaxUs = m_timing.cacheUpdMaxUs;
        m_snapshot.queueWaitAvgUs = totalOps > 0 ? m_timing.queueWaitUs / totalOps : 0;
        m_snapshot.queueWaitMaxUs = m_timing.queueWaitMaxUs;
        m_snapshot.updOver1ms = m_timing.updOver1ms;
        m_snapshot.updOver5ms = m_timing.updOver5ms;
        m_snapshot.updOver10ms = m_timing.updOver10ms;
        m_snapshot.entityCount = m_entityQueues.size();
        m_snapshot.rejectedUpdates = m_rejectedUpdates;
        m_snapshot.rejectedGets = m_rejectedGets;
        m_snapshot.rejectedExtendTTLs = m_rejectedExtendTTLs;
        m_snapshot.rejectedTotal = m_rejectedUpdates + m_rejectedGets + m_rejectedExtendTTLs;
        m_snapshot.valid = true;

        // Detailed per-worker log at LEVEL_2 (debug) — not for production
        TH_LOG(DLT_INFORMATION, DLM_LEVEL_2,
            "ShardWorker[%d] timing: proc=%lu%% idle=%lu%% other=%lu%% "
            "| ops=%lu gets=%lu (hit=%lu miss=%lu) updates=%lu "
            "| fetchQ=%lu fetchQDrops=%lu "
            "| fetchReqs=%lu found=%lu not=%lu "
            "| cacheGet=%lu cacheUpd=%lu cacheChk=%lu complete=%lu qWait=%lu qWaitMax=%lu "
            "| max: upd=%lu "
            "| tail: >1ms=%lu >2ms=%lu >5ms=%lu >10ms=%lu "
            "| dropped=%lu "
            "| rejected=%lu(upd=%lu get=%lu ttl=%lu) "
            "| entities=%u totalEq=%zu",
            m_workerIndex,
            procPct, idlePct, otherUs * 100 / wallUs,
            ops, m_timing.getCount, m_timing.cacheHits, m_timing.cacheMisses, m_timing.updateCount,
            m_timing.fetchQueued, m_timing.fetchDropped,
            m_timing.fetchRequests, m_timing.fetchRequestsFound, m_timing.fetchRequestsNotFound,
            m_timing.getCount > 0 ? m_timing.cacheGetUs / m_timing.getCount : 0,
            m_timing.updateCount > 0 ? m_timing.cacheUpdateUs / m_timing.updateCount : 0,
            m_timing.updateCount > 0 ? m_timing.cacheCheckUs / m_timing.updateCount : 0,
            totalOps > 0 ? m_timing.completionSigUs / totalOps : 0,
            totalOps > 0 ? m_timing.queueWaitUs / totalOps : 0,
            m_timing.queueWaitMaxUs,
            m_timing.cacheUpdMaxUs,
            m_timing.updOver1ms, m_timing.updOver2ms, m_timing.updOver5ms, m_timing.updOver10ms,
            dropped,
            m_snapshot.rejectedTotal, m_snapshot.rejectedUpdates, m_snapshot.rejectedGets, m_snapshot.rejectedExtendTTLs,
            activeEntities, m_entityQueues.size());

        // Worker 0 emits a single aggregate summary line at LEVEL_1 (production)
        if (m_workerIndex == 0 && !m_peerWorkers.empty())
        {
            uint64_t totOps = 0, totDropped = 0, totGets = 0, totUpdates = 0, totHits = 0, totMisses = 0;
            uint64_t totFetchQueued = 0, totFetchDropped = 0;
            uint64_t totFetchRequests = 0, totFetchRequestsFound = 0, totFetchRequestsNotFound = 0;
            uint64_t totOver1ms = 0, totOver5ms = 0, totOver10ms = 0, totEntities = 0;
            uint64_t totRejUpd = 0, totRejGet = 0, totRejTtl = 0, totRejected = 0;
            uint64_t minProc = 100, maxProc = 0, sumProc = 0;
            uint64_t maxUpdUs = 0, maxQWaitUs = 0;
            uint64_t sumUpdAvg = 0, sumGetAvg = 0, sumQWaitAvg = 0;
            uint32_t validWorkers = 0;

            for (auto* peer : m_peerWorkers)
            {
                const auto& s = peer->GetSnapshot();
                if (!s.valid) continue;
                validWorkers++;
                totOps += s.ops;
                totDropped += s.dropped;
                totGets += s.getCount;
                totUpdates += s.updateCount;
                totHits += s.cacheHits;
                totMisses += s.cacheMisses;
                totFetchQueued += s.fetchQueued;
                totFetchDropped += s.fetchDropped;
                totFetchRequests += s.fetchRequests;
                totFetchRequestsFound += s.fetchRequestsFound;
                totFetchRequestsNotFound += s.fetchRequestsNotFound;
                totOver1ms += s.updOver1ms;
                totOver5ms += s.updOver5ms;
                totOver10ms += s.updOver10ms;
                totEntities += s.entityCount;
                totRejUpd += s.rejectedUpdates;
                totRejGet += s.rejectedGets;
                totRejTtl += s.rejectedExtendTTLs;
                totRejected += s.rejectedTotal;
                sumProc += s.procPct;
                if (s.procPct < minProc) minProc = s.procPct;
                if (s.procPct > maxProc) maxProc = s.procPct;
                if (s.cacheUpdMaxUs > maxUpdUs) maxUpdUs = s.cacheUpdMaxUs;
                if (s.queueWaitMaxUs > maxQWaitUs) maxQWaitUs = s.queueWaitMaxUs;
                sumUpdAvg += s.cacheUpdAvgUs;
                sumGetAvg += s.cacheGetAvgUs;
                sumQWaitAvg += s.queueWaitAvgUs;
            }

            uint64_t hitPct = (totGets > 0) ? (totHits * 100 / totGets) : 0;
            uint64_t avgProc = (validWorkers > 0) ? sumProc / validWorkers : 0;
            uint64_t avgUpd = (validWorkers > 0) ? sumUpdAvg / validWorkers : 0;
            uint64_t avgGet = (validWorkers > 0) ? sumGetAvg / validWorkers : 0;
            uint64_t avgQWait = (validWorkers > 0) ? sumQWaitAvg / validWorkers : 0;

            TH_LOG(DLT_INFORMATION, DLM_LEVEL_1,
                "ShardDispatcher[%uw] 10s: "
                "ops=%lu (get=%lu upd=%lu hit=%lu%%) "
                "| fetchQ=%lu fetchQDrops=%lu "
                "| fetchReqs=%lu found=%lu not=%lu "
                "| proc=%lu-%lu%% avg=%lu%% "
                "| cacheUpd avg=%luus max=%luus | cacheGet avg=%luus "
                "| queueWait avg=%luus max=%luus "
                "| tail: >1ms=%lu >5ms=%lu >10ms=%lu "
                "| dropped=%lu "
                "| rejected=%lu(upd=%lu get=%lu ttl=%lu) "
                "| entities=%lu",
                validWorkers,
                totOps, totGets, totUpdates, hitPct,
                totFetchQueued, totFetchDropped,
                totFetchRequests, totFetchRequestsFound, totFetchRequestsNotFound,
                minProc, maxProc, avgProc,
                avgUpd, maxUpdUs, avgGet,
                avgQWait, maxQWaitUs,
                totOver1ms, totOver5ms, totOver10ms,
                totDropped,
                totRejected, totRejUpd, totRejGet, totRejTtl,
                totEntities);
        }
    }

    m_timing.Reset(Clock::now());
    m_processedCount.store(0, std::memory_order_relaxed);
    m_droppedCount.store(0, std::memory_order_relaxed);
    m_rejectedUpdates = 0;
    m_rejectedGets = 0;
    m_rejectedExtendTTLs = 0;
}

void ShardWorker::RecordUpdateLatency(uint64_t updateUs)
{
    m_timing.cacheUpdateUs += updateUs;
    if (updateUs > m_timing.cacheUpdMaxUs) m_timing.cacheUpdMaxUs = updateUs;
    if (updateUs > 1000) m_timing.updOver1ms++;
    if (updateUs > 2000) m_timing.updOver2ms++;
    if (updateUs > 5000) m_timing.updOver5ms++;
    if (updateUs > 10000) m_timing.updOver10ms++;
}

bool ShardWorker::SubmitEntityFetch(WorkItemPtr& item,
                                    EntityQueue& eq,
                                    FetchRequest req,
                                    EntityQueue::OpCategory cat)
{
    if (!eq.enqueue(item, cat))
    {
        eq.fetchInFlight = false;
        m_droppedCount.fetch_add(1, std::memory_order_relaxed);
        m_timing.fetchDropped++;
        return false; // item stays non-null, caller sets failure result
    }
    m_timing.fetchRequests++;
    item = nullptr; // ownership transferred to entity queue
    m_fetchPool->Submit(std::move(req));
    return true;
}

FetchRequest ShardWorker::BuildFetchRequest(IStorageEngine_Ptr engine,
                                            std::string_view entityTypeName,
                                            std::string_view orgId,
                                            std::string_view entityValue,
                                            int32_t crcForSharding,
                                            bool addToCacheWhenNotFound,
                                            boost::optional<bool> allowWriteToDB,
                                            std::string_view entityKey,
                                            EntityQueue& eq)
{
    FetchRequest req;
    req.engine = std::move(engine);
    req.entityTypeName = entityTypeName;
    req.orgId = orgId;
    req.entityValue = entityValue;
    req.crcForSharding = crcForSharding;
    req.addToCacheWhenNotFound = addToCacheWhenNotFound;
    req.allowWriteToDB = allowWriteToDB;
    req.entityKey = entityKey;
    req.targetWorker = this;
    req.entityQueue = &eq;
    return req;
}

void ShardWorker::FlushPendingGets(std::vector<WorkItemPtr>& pendingGets, const std::string& entityKey)
{
    if (pendingGets.empty())
        return;

    auto const & firstOp = std::get<GetRecordOp>(*pendingGets[0]);
    if (IStorageEngine_Ptr engine = ResolveEngine(firstOp.entityTypeName, "ProcessEntityBatch");
        engine != nullptr)
    {
        EntityQueue& eq = GetOrCreateEntityQueue(entityKey);
        CoalesceGets(engine, entityKey, eq, pendingGets);
    }
    else
    {
        for (auto*& item : pendingGets)
        {
            auto& getOp = std::get<GetRecordOp>(*item);
            getOp.result.readResult = SeReadResult::FAIL;
            CompleteAndMaybeDelete(item);
        }
    }
    pendingGets.clear();
}

void ShardWorker::ProcessEntityBatch(const std::string& entityKey, std::vector<WorkItemPtr>& ops)
{
    // Process ops in order, coalescing only consecutive Gets.
    // A mutating op (Update, etc.) between Gets forces a flush so that
    // the later Get sees the result of the mutation.
    std::vector<WorkItemPtr> pendingGets;

    for (auto*& item : ops)
    {
        if (std::holds_alternative<GetRecordOp>(*item))
        {
            pendingGets.push_back(item);
            item = nullptr; // ownership moved to pendingGets
        }
        else
        {
            // Flush any accumulated Gets before processing a mutating op
            FlushPendingGets(pendingGets, entityKey);
            ProcessWorkItem(item);
            if (item != nullptr)
            {
                CompleteAndMaybeDelete(item);
            }
        }
    }

    // Flush trailing Gets
    FlushPendingGets(pendingGets, entityKey);
}

void ShardWorker::CoalesceGets(IStorageEngine_Ptr engine, const std::string& entityKey,
                               EntityQueue& eq, std::vector<WorkItemPtr>& getOps)
{
    // If a fetch is already in-flight, queue all behind it
    if (eq.fetchInFlight)
    {
        for (auto*& item : getOps)
        {
            if (!eq.enqueue(item, EntityQueue::OpCategory::Get))
            {
                auto& getOp = std::get<GetRecordOp>(*item);
                getOp.result.readResult = SeReadResult::FAIL;
                CompleteAndMaybeDelete(item);
                m_droppedCount.fetch_add(1, std::memory_order_relaxed);
                if (m_entityQueueDropWarning.CanPerformAction())
                    TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                           "ShardWorker[%u]: entity queue full, dropping ops (pending=%zu/%u, entity=%s)",
                           m_workerIndex, eq.pendingOps.size(), eq.maxPendingOps, entityKey.c_str());
            }
            else
            {
                item = nullptr; // ownership transferred to entity queue
                m_coalescedCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
        return;
    }

    // Try cache — one lookup serves all Gets in this batch
    auto& firstOp = std::get<GetRecordOp>(*getOps[0]);
    GetRecordResult result;
    result.readResult = SeReadResult::FAIL;
    SE_PerfInfo perfInfo;
    perfInfo.orgId = firstOp.orgId;
    result.readResult = engine->TryGetFromCache(
        firstOp.entityValue, firstOp.crcForSharding,
        result.mainBlob, result.trustTagBlob, result.personaDbBlob,
        result.infoTagBlob, result.metaDataBlob,
        result.requestForWrongShard, result.ttlSeconds, &perfInfo);

    if (result.readResult == SeReadResult::SUCCESS)
    {
        // Cache hit — resolve all Gets with the same result
        for (size_t i = 0; i < getOps.size(); ++i)
        {
            eq.opsProcessed++;
            if (eq.opsProcessed > 1)
                m_coalescedCount.fetch_add(1, std::memory_order_relaxed);

            auto& op = std::get<GetRecordOp>(*getOps[i]);
            op.result = result;
            CompleteAndMaybeDelete(getOps[i]);
        }
        return;
    }

    // Cache miss — dispatch first to fetch pool, queue the rest
    eq.fetchInFlight = true;

    FetchRequest req;
    req.engine = engine;
    req.entityTypeName = firstOp.entityTypeName;
    req.orgId = firstOp.orgId;
    req.entityValue = firstOp.entityValue;
    req.crcForSharding = firstOp.crcForSharding;
    req.addToCacheWhenNotFound = firstOp.addToCacheWhenNotFound;
    req.allowWriteToDB = firstOp.allowWriteToDB;
    req.entityKey = entityKey;
    req.targetWorker = this;
    req.entityQueue = &eq;

    // Queue ALL Gets behind the fetch (first included)
    for (auto*& item : getOps)
    {
        if (!eq.enqueue(item, EntityQueue::OpCategory::Get))
        {
            auto& getOp = std::get<GetRecordOp>(*item);
            getOp.result.readResult = SeReadResult::FAIL;
            CompleteAndMaybeDelete(item);
            m_droppedCount.fetch_add(1, std::memory_order_relaxed);
            if (m_entityQueueDropWarning.CanPerformAction())
                TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                       "ShardWorker[%u]: entity queue full, dropping ops (pending=%zu/%u, entity=%s)",
                       m_workerIndex, eq.pendingOps.size(), eq.maxPendingOps, entityKey.c_str());
        }
        else
        {
            item = nullptr; // ownership transferred to entity queue
        }
    }

    m_timing.fetchRequests++;
    m_fetchPool->Submit(std::move(req));
}

void ShardWorker::ProcessWorkItem(WorkItemPtr& item, const std::string* entityKeyPtr, EntityQueue* entityQueue)
{
    std::visit(absl::Overload{
        [this, &item, entityKeyPtr, entityQueue](GetRecordOp&)      { ProcessGet(item, entityKeyPtr, entityQueue); },
        [this, &item](GetSmartIdIrSnapshotOp&)                      { ProcessGetSmartIdIrSnapshot(item); },
        [this, &item, entityKeyPtr, entityQueue](UpdateRecordOp&)   { ProcessUpdate(item, entityKeyPtr, entityQueue); },
        [this, &item](CacheHintOp&)                                 { ProcessCacheHint(item); },
        [this, &item, entityKeyPtr, entityQueue](ExtendTTLOp&)      { ProcessExtendTTL(item, entityKeyPtr, entityQueue); }
    }, *item);
}

std::string ShardWorker::MakeEntityKey(
    std::string_view engineName,
    std::string_view orgId,
    std::string_view entityValue,
    bool isGlobalScope) const
{
    // Global entities are shared across orgs — omit orgId so all orgs share
    // the same entity queue and cache entry for a given entity value.
    // Local entities include orgId to keep per-org data isolated.
    std::string key;
    if (isGlobalScope)
    {
        key.reserve(engineName.size() + 2 + entityValue.size());
        key.append(engineName);
        key.append("::");
        key.append(entityValue);
    }
    else
    {
        key.reserve(engineName.size() + 1 + orgId.size() + 1 + entityValue.size());
        key.append(engineName);
        key.push_back(':');
        key.append(orgId);
        key.push_back(':');
        key.append(entityValue);
    }
    return key;
}

EntityQueue& ShardWorker::GetOrCreateEntityQueue(const std::string& key)
{
    auto [it, inserted] = m_entityQueues.try_emplace(key);
    auto& eq = it->second;
    if (inserted)
    {
        eq.maxPendingOps = m_entityQueueDepth;
        eq.maxPendingUpdates = m_maxUpdatesPerEntity;
        eq.maxPendingGets = m_maxGetsPerEntity;
        eq.maxPendingExtendTTLs = m_maxExtendTTLsPerEntity;
    }
    eq.touch();
    return eq;
}

void ShardWorker::MarkEntityReady(const std::string& key, EntityQueue& eq)
{
    if (!eq.hasPendingOps() || eq.fetchInFlight || eq.inReadyQueue)
        return;
    m_readyEntities.push_back(key);
    eq.inReadyQueue = true;
}

void ShardWorker::CheckAndRunBackgroundOps()
{
    auto now = Clock::now();
    if (now - m_lastBackgroundOpsTime < std::chrono::seconds(DataCacheAndJournalHandler::GetBgProcessingPeriod()))
        return;

    m_lastBackgroundOpsTime = now;

    // Flush dirty writes via the dedicated write thread (non-blocking).
    // The worker snapshots and serializes write data on this thread so the
    // write thread never touches EntityCache or CacheRecord blobs.
    for (const auto& [name, engine] : m_workerEngines)
    {
        if (engine && engine->IsInitialized())
        {
            auto entries = engine->SnapshotWriteEntries();
            if (!entries.empty())
            {
                WriteRequest req;
                req.engineName = name;
                req.engine = engine;
                req.entries = std::move(entries);
                if (!m_writeThread->Submit(std::move(req)))
                {
                    // Submit moved entries back into req on failure.
                    // Re-insert into the write map so they'll be retried.
                    ReinsertFailedWriteEntries(engine, req.entries); // NOSONAR: SQ thinks that "req" has been moved from, but it's mistaken.
                    TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                           "ShardWorker[%u]: write queue full for %s, "
                           "re-inserted %zu entries for retry",
                           m_workerIndex, name.c_str(), req.entries.size()); // NOSONAR: Ditto
                }
            }
        }
    }

    // Periodic cache maintenance: CleanCache (evict expired entries) +
    // CompactCache (pack records). In shard dispatch mode, the DCJH background
    // thread is disabled so the worker must do this.
    for (const auto& [name, engine] : m_workerEngines)
    {
        if (engine && engine->IsInitialized())
        {
            engine->DoPeriodicMaintenance();
        }
    }

    // Evict stale entity queue entries that haven't been accessed recently
    EvictStaleEntityQueues();
}

void ShardWorker::ProcessGet(WorkItemPtr& item, const std::string* preEntityKeyPtr, EntityQueue* preEqPtr)
{
    auto& op = std::get<GetRecordOp>(*item);
    m_timing.getCount++;

    EntityQueue* eqPtr = nullptr;
    const std::string* entityKeyPtr = nullptr;
    std::string localKey; // only used if we need to compute the entity key locally
    if ((preEqPtr != nullptr) && (preEntityKeyPtr != nullptr))
    {
        // Use pre-computed EntityQueue and entity key from caller
        eqPtr = preEqPtr;
        entityKeyPtr = preEntityKeyPtr;
    }
    else
    {
        localKey = MakeEntityKey(op.entityTypeName, op.orgId, op.entityValue, op.isGlobalScope);
        entityKeyPtr = &localKey;
        eqPtr = &GetOrCreateEntityQueue(localKey);
    }
    ASSERT(entityKeyPtr != nullptr && eqPtr != nullptr);
    EntityQueue& eq = *eqPtr;
    std::string const &key = *entityKeyPtr;

    // If a fetch is already in-flight for this entity, queue and coalesce
    if (eq.fetchInFlight)
    {
        if (!eq.enqueue(item, EntityQueue::OpCategory::Get))
        {
            op.result.readResult = SeReadResult::FAIL;
            // item stays non-null, caller will signal completion
            m_droppedCount.fetch_add(1, std::memory_order_relaxed);
            m_timing.fetchDropped++;
            if (m_entityQueueDropWarning.CanPerformAction())
                TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                       "ShardWorker[%u]: entity queue full in ProcessGet (pending=%zu/%u)",
                       m_workerIndex, eq.pendingOps.size(), eq.maxPendingOps);
        }
        else
        {
            m_timing.fetchQueued++;
            item = nullptr; // ownership transferred to entity queue
            m_coalescedCount.fetch_add(1, std::memory_order_relaxed);
        }
        return;
    }

    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessGet");

    if (!engine)
    {
        op.result.readResult = SeReadResult::FAIL;
        return; // item stays non-null, caller signals completion
    }
    // Try the local cache first — this is non-blocking (no Aerospike I/O)
    SE_PerfInfo perfInfo;
    perfInfo.orgId = op.orgId;

    auto t4 = Clock::now();
    op.result.readResult = engine->TryGetFromCache(
        op.entityValue, op.crcForSharding,
        op.result.mainBlob, op.result.trustTagBlob, op.result.personaDbBlob,
        op.result.infoTagBlob, op.result.metaDataBlob,
        op.result.requestForWrongShard, op.result.ttlSeconds, &perfInfo);
    auto t5 = Clock::now();
    m_timing.cacheGetUs += std::chrono::duration_cast<std::chrono::microseconds>(t5 - t4).count();

    if (op.result.readResult == SeReadResult::SUCCESS)
    {
        // Cache hit — return immediately
        m_timing.cacheHits++;
        eq.opsProcessed++;
        if (eq.opsProcessed > 1)
            m_coalescedCount.fetch_add(1, std::memory_order_relaxed);
        return; // item stays non-null, caller signals completion
    }

    m_timing.cacheMisses++;

    // Cache miss — dispatch to fetch pool for async Aerospike read
    eq.fetchInFlight = true;

    FetchRequest req = BuildFetchRequest(engine, op.entityTypeName, op.orgId, op.entityValue,
                                         op.crcForSharding, op.addToCacheWhenNotFound,
                                         op.allowWriteToDB, key, eq);

    if (!SubmitEntityFetch(item, eq, std::move(req), EntityQueue::OpCategory::Get))
    {
        op.result.readResult = SeReadResult::FAIL;
        // item stays non-null, caller signals completion
        return;
    }
}

void ShardWorker::ProcessGetSmartIdIrSnapshot(WorkItemPtr& item)
{
    auto& op = std::get<GetSmartIdIrSnapshotOp>(*item);
    op.result.readResult = SeReadResult::FAIL;

    // Guard against use-after-free: if the caller (Thrift handler) has already
    // timed out and abandoned, returnPtr may be a dangling pointer to destroyed
    // stack memory. Skip the engine call entirely in that case.
    if (op.completion.state.load(std::memory_order_acquire) == CompletionEvent::ABANDONED)
        return;

    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessGetSmartIdIrSnapshot");
    if (engine && op.returnPtr != nullptr)
    {
        SE_PerfInfo perfInfo;
        perfInfo.orgId = op.orgId;
        op.result.readResult = engine->GetSmartIdIrSnapshot(
            op.orgId, op.entityValue, op.crcForSharding,
            *op.returnPtr, op.result.requestForWrongShard, &perfInfo);
    }
    // item stays non-null, caller signals completion
}

void ShardWorker::ProcessUpdate(WorkItemPtr& item, const std::string* preEntityKeyPtr, EntityQueue* preEqPtr)
{
    auto& op = std::get<UpdateRecordOp>(*item);
    m_timing.updateCount++;

    EntityQueue* eqPtr = nullptr;
    const std::string* entityKeyPtr = nullptr;
    std::string localKey; // only used if we need to compute the entity key locally
    if ((preEqPtr != nullptr) && (preEntityKeyPtr != nullptr))
    {
        // Use pre-computed EntityQueue and entity key from caller
        eqPtr = preEqPtr;
        entityKeyPtr = preEntityKeyPtr;
    }
    else
    {
        localKey = MakeEntityKey(op.entityTypeName, op.orgId, op.entityValue, op.isGlobalScope);
        entityKeyPtr = &localKey;
        eqPtr = &GetOrCreateEntityQueue(localKey);
    }
    ASSERT(entityKeyPtr != nullptr && eqPtr != nullptr);
    EntityQueue& eq = *eqPtr;
    std::string const &key = *entityKeyPtr;

    // If a fetch is in-flight, queue behind it — no engine resolution needed
    if (eq.fetchInFlight)
    {
        if (!eq.enqueue(item, EntityQueue::OpCategory::Update))
        {
            op.result.success = false;
            // item stays non-null, caller signals completion
            m_droppedCount.fetch_add(1, std::memory_order_relaxed);
            m_timing.fetchDropped++;
            if (m_entityQueueDropWarning.CanPerformAction())
                TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                       "ShardWorker[%u]: entity queue full in ProcessUpdate (pending=%zu/%u)",
                       m_workerIndex, eq.pendingOps.size(), eq.maxPendingOps);
        }
        else
        {
            m_timing.fetchQueued++;
            item = nullptr; // ownership transferred to entity queue
        }
        return;
    }

    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessUpdate");

    if (!engine || !op.data)
    {
        op.result.success = false;
        return; // item stays non-null, caller signals completion
    }

    // Check if entity is in cache map — unlike TryGetFromCache, this returns true
    // even for empty CacheRecords (new entities not yet in Aerospike). This prevents
    // an infinite fetch loop where TryGetFromCache returns RECORD_NOT_FOUND for empty
    // records, causing repeated fetches that never resolve.
    auto t4 = Clock::now();
    bool alreadyCached = engine->IsEntityInCache(op.entityValue);
    auto t5 = Clock::now();
    m_timing.cacheCheckUs += std::chrono::duration_cast<std::chrono::microseconds>(t5 - t4).count();

    if (alreadyCached)
    {
        eq.isDirty = true;
        eq.dirtyCount++;
        eq.intervalUpdateCount++;

        SE_PerfInfo perfInfo;
        perfInfo.orgId = op.orgId;

        auto t6 = Clock::now();
        op.result.success = engine->UpdateTheRecord(
            op.orgId, op.entityValue, *op.data, op.crcForSharding, &perfInfo);
        op.result.cacheRecordUpdateLimitReached = perfInfo.cacheRecordUpdateLimitReached;
        auto t7 = Clock::now();
        uint64_t thisUpdUs = std::chrono::duration_cast<std::chrono::microseconds>(t7 - t6).count();
        RecordUpdateLatency(thisUpdUs);

        return; // item stays non-null, caller signals completion
    }

    // Cold entity — fetch first, then update after fetch completes
    eq.fetchInFlight = true;

    boost::optional<bool> allowWriteToDB = (op.data && op.data->__isset.allowWriteToDB)
        ? boost::optional<bool>(op.data->allowWriteToDB) : boost::none;
    FetchRequest req = BuildFetchRequest(engine, op.entityTypeName, op.orgId, op.entityValue,
                                         op.crcForSharding, true, allowWriteToDB, key, eq);

    if (!SubmitEntityFetch(item, eq, std::move(req), EntityQueue::OpCategory::Update))
    {
        op.result.success = false;
        // item stays non-null, caller signals completion
        return;
    }
}

void ShardWorker::ProcessCacheHint(WorkItemPtr& item)
{
    auto& op = std::get<CacheHintOp>(*item);
    // CacheHint is fire-and-forget — CompletionEvent is pre-abandoned.
    // The worker always deletes the item.
    op.result.success = true;

    std::string key = MakeEntityKey(op.entityTypeName, op.orgId, op.entityValue, op.isGlobalScope);
    EntityQueue& eq = GetOrCreateEntityQueue(key);

    // Already fetching or fetch pending — nothing more to do
    if (eq.fetchInFlight)
        return;

    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessCacheHint");
    if (!engine)
        return;

    // Check if entity is already in cache map (even if empty/new)
    bool alreadyCached = engine->IsEntityInCache(op.entityValue);

    if (alreadyCached)
        return;

    // Cold entity — dispatch async fetch to warm the cache
    eq.fetchInFlight = true;

    FetchRequest req = BuildFetchRequest(engine, op.entityTypeName, op.orgId, op.entityValue,
                                         op.crcForSharding, true, op.allowWriteToDB, key, eq);

    m_timing.fetchRequests++;
    m_fetchPool->Submit(std::move(req));
}

void ShardWorker::ProcessExtendTTL(WorkItemPtr& item, const std::string* preEntityKeyPtr, EntityQueue* preEqPtr)
{
    auto& op = std::get<ExtendTTLOp>(*item);

    EntityQueue* eqPtr = nullptr;
    const std::string* entityKeyPtr = nullptr;
    std::string localKey; // only used if we need to compute the entity key locally
    if ((preEqPtr != nullptr) && (preEntityKeyPtr != nullptr))
    {
        // Use pre-computed EntityQueue and entity key from caller
        eqPtr = preEqPtr;
        entityKeyPtr = preEntityKeyPtr;
    }
    else
    {
        localKey = MakeEntityKey(op.entityTypeName, op.orgId, op.entityValue, op.isGlobalScope);
        entityKeyPtr = &localKey;
        eqPtr = &GetOrCreateEntityQueue(localKey);;
    }
    ASSERT(entityKeyPtr != nullptr && eqPtr != nullptr);
    EntityQueue& eq = *eqPtr;
    std::string const &key = *entityKeyPtr;

    // If a fetch is in-flight, queue behind it — no engine resolution needed
    if (eq.fetchInFlight)
    {
        if (!eq.enqueue(item, EntityQueue::OpCategory::ExtendTTL))
        {
            op.result.success = false;
            // item stays non-null, caller signals completion
            m_droppedCount.fetch_add(1, std::memory_order_relaxed);
            if (m_entityQueueDropWarning.CanPerformAction())
                TH_LOG(DLT_WARNING, DLM_LEVEL_1,
                       "ShardWorker[%u]: entity queue full in ProcessExtendTTL (pending=%zu/%u)",
                       m_workerIndex, eq.pendingOps.size(), eq.maxPendingOps);
        }
        else
        {
            item = nullptr; // ownership transferred to entity queue
        }
        return;
    }

    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessExtendTTL");
    if (!engine)
    {
        op.result.success = false;
        return; // item stays non-null, caller signals completion
    }

    // Check if entity is in cache map — use IsEntityInCache (not TryGetFromCache)
    // to detect empty CacheRecords for new entities correctly
    bool alreadyCached = engine->IsEntityInCache(op.entityValue);

    if (alreadyCached)
    {
        // Record is in cache — safe to extend TTL without blocking
        engine->ExtendTheRecordTTL(op.orgId, op.entityValue, op.crcForSharding,
                                   op.extendedTTLInSeconds, op.allowWriteToDB);
        op.result.success = true;
        return; // item stays non-null, caller signals completion
    }

    // Cold entity — need to fetch first, then extend TTL after fetch completes
    eq.fetchInFlight = true;

    FetchRequest req = BuildFetchRequest(engine, op.entityTypeName, op.orgId, op.entityValue,
                                         op.crcForSharding, true, op.allowWriteToDB, key, eq);

    if (!SubmitEntityFetch(item, eq, std::move(req), EntityQueue::OpCategory::ExtendTTL))
    {
        op.result.success = false;
        // item stays non-null, caller signals completion
        return;
    }
}

void ShardWorker::ReinsertFailedWriteEntries(const IStorageEngine_Ptr& engine,
                                              std::vector<SerializedWriteEntry>& entries)
{
    // Delegate to the engine — it knows how to put entries back into
    // the concurrent write map and reset DataState to DataModified.
    engine->ReinsertWriteEntries(entries);
}

void ShardWorker::EvictStaleEntityQueues()
{
    time_t now = ::time(nullptr);

    // Align entity queue eviction with the cache clean period so that
    // entity queues don't outlive their cached records.
    // Formula matches DataCacheAndJournalHandler::m_cacheCleanPeriod.
    time_t cacheTtl = StorageEngineConfig::GetOption(EntityConfigProperty::CacheTtl);
    time_t cacheCleanPeriod = std::min(cacheTtl / 4,
                                       DataCacheAndJournalHandler::GetCacheCompactTimeout());
    cacheCleanPeriod = std::max(cacheCleanPeriod, static_cast<time_t>(1));

    std::erase_if(m_entityQueues, [now, cacheCleanPeriod](const auto& pair) {
        const auto& eq = pair.second;
        // Safety: never evict an entity that has a fetch in flight or an
        // unprocessed completion. Raw pointers to this EntityQueue may exist
        // in FetchRequest (pool queue) or m_completionNotifyQueue while
        // fetchInFlight is true, and fetchCompletionReady is true until consumed.
        return !eq.hasPendingOps() &&
               !eq.fetchInFlight &&
               !eq.fetchCompletionReady.load(std::memory_order_acquire) &&
               (now - eq.lastAccessTime) > cacheCleanPeriod;
    });
}

IStorageEngine_Ptr ShardWorker::ResolveEngine(const std::string& entityTypeName, const char* methodName)
{
    // Use per-worker engine clones (own cache, NUMA-local)
    auto it = m_workerEngines.find(entityTypeName);
    if (it == m_workerEngines.end())
    {
        TH_LOG(DLT_ERROR, DLM_LEVEL_1,
               "ShardWorker[%u]::%s: can't find StorageEngine %s",
               m_workerIndex, methodName, entityTypeName.c_str());
        return {};
    }

    const auto& engine = it->second;
    if (engine == nullptr || !engine->IsInitialized())
    {
        TH_LOG(DLT_ERROR, DLM_LEVEL_1,
               "ShardWorker[%u]::%s: uninitialised StorageEngine %s",
               m_workerIndex, methodName, entityTypeName.c_str());
        return {};
    }

    return engine;
}

void ShardWorker::CompleteAndMaybeDelete(WorkItemPtr& item)
{
    auto t0 = Clock::now();
    if (auto* comp = getCompletionPtr(*item);
        comp == nullptr ||
        comp->complete())
    {
        // Caller abandoned — worker owns cleanup
        if (m_workItemPool != nullptr)
            m_workItemPool->release(item);
        else
            delete item; // NOSONAR — no pool configured
    }
    // else: caller (Thrift thread) will release/delete after reading result
    item = nullptr;
    auto t1 = Clock::now();
    m_timing.completionSigUs += std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    m_processedCount.fetch_add(1, std::memory_order_relaxed);
}

void ShardWorker::InitializeWorkerEngines()
{
    if (m_masterEngines == nullptr)
        return;

    for (auto& [name, masterEngine] : *m_masterEngines)
    {
        if (masterEngine && masterEngine->IsInitialized())
        {
            auto workerEngine = masterEngine->CreateWorkerInstance(m_numWorkers);
            if (workerEngine)
            {
                m_workerEngines[name] = std::move(workerEngine);
            }
            else
            {
                // Fallback: use master engine directly (e.g. mock that doesn't support cloning)
                m_workerEngines[name] = masterEngine;
            }
        }
    }

    TH_LOG(DLT_INFORMATION, DLM_LEVEL_1,
           "ShardWorker[%u]: initialized %zu worker engine clones",
           m_workerIndex, m_workerEngines.size());
}

void ShardWorker::ProcessFetchComplete(FetchCompleteOp& op)
{
    auto it = m_entityQueues.find(op.entityKey);
    if (it == m_entityQueues.end())
    {
        TH_LOG(DLT_WARNING, DLM_LEVEL_1,
               "ShardWorker[%u]::ProcessFetchComplete: no EntityQueue for %s",
               m_workerIndex, op.entityKey.c_str());
        return;
    }

    EntityQueue& eq = it->second;
    eq.fetchInFlight = false;
    eq.touch();

    bool entityExists = (op.result.readResult == SeReadResult::SUCCESS);
    if (entityExists && op.payload)
        m_timing.fetchRequestsFound++;
    else
        m_timing.fetchRequestsNotFound++;

    // Insert fetched record into cache on the worker thread — this ensures
    // CacheRecord is allocated on the worker's NUMA node for optimal locality.
    IStorageEngine_Ptr engine = ResolveEngine(op.entityTypeName, "ProcessFetchComplete");
    if (engine)
    {
        engine->InsertFetchedRecordIntoCache(
            op.entityValue, op.orgId, op.crcForSharding,
            std::move(op.payload), op.result.ttlSeconds, op.generationId,
            entityExists, op.addToCacheWhenNotFound, op.allowWriteToDB);
    }

    // Drain all pending ops, filling each result and signaling completion.
    // Note: ProcessUpdate/ProcessExtendTTL below will always take the warm
    // (cache-hit) path because InsertFetchedRecordIntoCache above just
    // populated the cache. They will never set pending = nullptr (cold path)
    // since the entity is guaranteed to be in cache at this point.
    while (eq.hasPendingOps())
    {
        ShardWorkItem* pending = eq.dequeue();
        if (pending == nullptr)
            break;

        // These ops bypass fetch coalescing and are never enqueued as pending:
        // - GetSmartIdIrSnapshotOp resolves synchronously via the engine
        std::visit(absl::Overload{
            [&op](GetRecordOp& pendingOp)     { pendingOp.result = op.result; },
            [this, &pending](UpdateRecordOp&) { ProcessUpdate(pending); },
            [](CacheHintOp& pendingOp)        { pendingOp.result.success = true; },
            [this, &pending](ExtendTTLOp&)    { ProcessExtendTTL(pending); },
            [](GetSmartIdIrSnapshotOp const&) { assert(!"Unexpected op type in pending queue"); }
        }, *pending);

        if (pending != nullptr)
        {
            CompleteAndMaybeDelete(pending);
        }
    }

    // If any new runnable work remained after draining, enqueue it.
    MarkEntityReady(op.entityKey, eq);
}

bool ShardWorker::IsClonedFromMasterEngine(std::string_view name, const IStorageEngine_Ptr& worker) const
{
    if (m_masterEngines == nullptr || !worker)
        return false;

    if (auto it = m_masterEngines->find(name);
        it != m_masterEngines->end() &&
        it->second.get() == worker.get())
    {
        // worker pointer is the same as the master pointer - not cloned
        return false;
    }
    return true;
}
