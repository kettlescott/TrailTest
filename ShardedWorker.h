/******************************************************************************
 *
 * Copyright (C) 2026 LexisNexis Risk Solutions
 *
 * Description:
 *   ShardWorker: a single-threaded worker that exclusively owns a set of
 *   logical shards. All cache operations for entities in its shards run
 *   on this thread — no locks required.
 *
 *   The worker drains its SPSC input queue, dispatches operations to the
 *   appropriate IStorageEngine, and signals CompletionEvent on each work item.
 *
 *   Phase 2: Maintains per-entity queues to coalesce duplicate DB fetches
 *   and tracks background maintenance ops (flush/compact/clean).
 *
 *   Phase 3: Per-entity queue depth cap, condition_variable sleep/wake,
 *   fetch coalescing observability.
 *
 ******************************************************************************/
#pragma once

#include "ShardWorkItem.h"
#include "EntityQueue.h"
#include "IStorageEngine.h"
#include "FetchThreadPool.h"
#include "DataCacheAndJournalHandler.h"
#include <atomic_queue/atomic_queue.h>
#include <Utility/PeriodMonitor.h>

#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <functional>
#include <vector>
#include <deque>
#include <string>
#include <string_view>
#include <memory>
#include <map>
#include <unordered_map>
#include <chrono>

// Forward declarations
namespace threatmetrix::thrift { class UpdateTheRecordData; }
class ShardWriteThread;

class ShardWorker
{
public:
    static constexpr unsigned DEFAULT_QUEUE_DEPTH = 8192;
    static constexpr uint32_t DEFAULT_ENTITY_QUEUE_DEPTH = EntityQueue::DEFAULT_MAX_PENDING_OPS;

    explicit ShardWorker(uint32_t workerIndex, uint32_t numWorkers, uint32_t numShardQueues = 1,
                         uint32_t queueDepthPerShard = DEFAULT_QUEUE_DEPTH,
                         uint32_t entityQueueDepth = DEFAULT_ENTITY_QUEUE_DEPTH);
    ~ShardWorker();

    // Non-copyable, non-movable
    ShardWorker(const ShardWorker&) = delete;
    ShardWorker& operator=(const ShardWorker&) = delete;

    using StorageEngineMap = std::map<std::string, IStorageEngine_Ptr, std::less<>>;
    // Provide the mapping from entityTypeName -> IStorageEngine
    // Must be called before Start()
    void SetStorageEngines(const StorageEngineMap* engines) { m_masterEngines = engines; }

    // Set the fetch thread pool for async DB reads (optional — nullptr = synchronous)
    void SetFetchThreadPool(FetchThreadPool* pool) { m_fetchPool = pool; }

    // Set the write thread for async DB writes (optional — nullptr = synchronous DoCacheCommit)
    void SetWriteThread(ShardWriteThread* wt) { m_writeThread = wt; }

    // Set the pre-allocated work item pool (optional — nullptr = fall back to delete)
    void SetWorkItemPool(class ShardWorkItemPool* pool) { m_workItemPool = pool; }

    // Set per-entity admission caps (must be called before Start)
    void SetEntityCaps(uint32_t maxUpdates, uint32_t maxGets, uint32_t maxExtendTTLs)
    {
        m_maxUpdatesPerEntity = maxUpdates;
        m_maxGetsPerEntity = maxGets;
        m_maxExtendTTLsPerEntity = maxExtendTTLs;
    }

    // Start the worker thread. coreId >= 0 sets CPU core affinity.
    void Start(int32_t coreId = -1);
    void Stop();

    // Wake the worker from sleep wait. Called by ShardDispatcher after
    // pushing a work item to reduce wakeup latency.
    void Wake();

    // Called by fetch pool threads after writing a completion into an
    // EntityQueue's fetchCompletion slot and setting fetchCompletionReady.
    // Pushes a notification so the worker knows exactly which entity to
    // check (no full scan needed).
    //
    // Lifecycle contract: the EntityQueue* is safe to hold because
    // fetchInFlight is true for the duration — EvictStaleEntityQueues
    // will not erase an entity while fetchInFlight is set.
    void NotifyCompletionReady(EntityQueue* eq)
    {
        if (!m_completionNotifyQueue.try_push(eq))
        {
            // Queue full — set fallback flag so the worker does a full scan.
            // The result is still safely in eq->fetchCompletion (ready flag set).
            m_hasPendingCompletions.store(true, std::memory_order_release);
        }
        Wake();
    }

    // Push to the shard queue determined by shard index (used by ShardDispatcher)
    bool TryPush(ShardWorkItem* item, uint32_t shard)
    {
        uint32_t localQ = shard % m_numShardQueues;
        return m_shardQueues[localQ]->try_push(item);
    }

    // Aggregate queue stats across all shard queues
    unsigned TotalQueueSize() const
    {
        unsigned total = 0;
        for (const auto& q : m_shardQueues) total += q->was_size();
        return total;
    }
    unsigned TotalQueueCapacity() const
    {
        unsigned total = 0;
        for (const auto& q : m_shardQueues) total += q->capacity();
        return total;
    }

    uint32_t GetWorkerIndex() const { return m_workerIndex; }

    // Stats
    uint64_t GetProcessedCount() const { return m_processedCount.load(std::memory_order_relaxed); }
    uint64_t GetDroppedCount() const { return m_droppedCount.load(std::memory_order_relaxed); }
    uint64_t GetCoalescedCount() const { return m_coalescedCount.load(std::memory_order_relaxed); }
    uint64_t GetStaleCount() const { return m_staleCount.load(std::memory_order_relaxed); }
    size_t GetEntityCacheSize() const { return m_entityQueues.size(); }

    // Aggregate reporting: worker 0 reads snapshots from all peers
    void SetPeerWorkers(const std::vector<ShardWorker*>& peers) { m_peerWorkers = peers; }

    // Snapshot of key stats published at each report interval.
    // Written by the owning worker thread, read by worker 0 — benign races on uint64_t are acceptable.
    struct WorkerSnapshot {
        uint64_t procPct{0};
        uint64_t ops{0};
        uint64_t dropped{0};
        uint64_t getCount{0};
        uint64_t updateCount{0};
        uint64_t cacheHits{0};
        uint64_t cacheMisses{0};
        uint64_t fetchQueued{0};
        uint64_t fetchDropped{0};
        uint64_t fetchRequests{0};
        uint64_t fetchRequestsFound{0};
        uint64_t fetchRequestsNotFound{0};
        uint64_t cacheUpdAvgUs{0};
        uint64_t cacheGetAvgUs{0};
        uint64_t cacheUpdMaxUs{0};
        uint64_t queueWaitAvgUs{0};
        uint64_t queueWaitMaxUs{0};
        uint64_t updOver1ms{0};
        uint64_t updOver5ms{0};
        uint64_t updOver10ms{0};
        uint64_t entityCount{0};
        uint64_t rejectedUpdates{0};
        uint64_t rejectedGets{0};
        uint64_t rejectedExtendTTLs{0};
        uint64_t rejectedTotal{0};
        bool valid{false};
    };
    const WorkerSnapshot& GetSnapshot() const { return m_snapshot; }

private:
    // The work item on the queue is a pointer to a heap-allocated ShardWorkItem.
    // We use a raw pointer because atomic_queue requires trivially copyable types,
    // and the ShardWorkItem (containing CompletionEvent) is move-only.
    // Ownership: the submitter allocates via new, the worker deletes after processing.
    using WorkItemPtr = ShardWorkItem*;

    // SPSC queue with runtime-configurable size.
    // Template params: T, Allocator, NIL, MAXIMIZE_THROUGHPUT, TOTAL_ORDER, SPSC
    using Queue = atomic_queue::AtomicQueueB<WorkItemPtr, std::allocator<WorkItemPtr>, nullptr, true, false, false>;

    // Access the input queue (shard queue 0 — backward compatible for tests)
    Queue& GetQueue() { return *m_shardQueues[0]; }
    friend class ShardWorkerTest;

    void Run(int32_t coreId);
    void SetCoreAffinity(int32_t coreId);
    void ProcessWorkItem(WorkItemPtr& item, const std::string* entityKey = nullptr, EntityQueue* entityQueue = nullptr);

    // Two-tier drain: fast pass-through from sub-queues into per-entity queues,
    // then round-robin processing across active entity queues.
    bool DrainShardQueuesToEntityQueues();
    void ProcessActiveEntities();

    // Drain all pending fetch completions from the notification queue
    // (and fallback scan if the queue overflowed). Returns true if any
    // completions were processed.
    bool DrainCompletionNotifications();
    EntityQueue::OpCategory ClassifyOp(const ShardWorkItem& item) const;

    void ProcessEntityBatch(const std::string& entityKey, std::vector<WorkItemPtr>& ops);
    void FlushPendingGets(std::vector<WorkItemPtr>& pendingGets, const std::string& entityKey);
    void CoalesceGets(IStorageEngine_Ptr engine, const std::string& entityKey,
                      EntityQueue& eq, std::vector<WorkItemPtr>& getOps);
    void ProcessGet(WorkItemPtr& item, const std::string* preEntityKeyPtr = nullptr, EntityQueue* preEqPtr = nullptr);
    void ProcessGetSmartIdIrSnapshot(WorkItemPtr& item);
    void ProcessUpdate(WorkItemPtr& item, const std::string* preEntityKeyPtr = nullptr, EntityQueue* preEqPtr = nullptr);
    void ProcessCacheHint(WorkItemPtr& item);
    void ProcessExtendTTL(WorkItemPtr& item, const std::string* preEntityKeyPtr = nullptr, EntityQueue* preEqPtr = nullptr);
    void EvictStaleEntityQueues();
    void ProcessFetchComplete(FetchCompleteOp& op);

    // Signal completion and conditionally delete a work item.
    // If the caller (Thrift thread) has already abandoned (timed out),
    // the worker deletes the item. Otherwise the caller will delete it.
    void CompleteAndMaybeDelete(WorkItemPtr& item);

    // Entity queue management — key omits orgId for SCOPE_GLOBAL entities so
    // the same global entity value is shared across all orgs
    std::string MakeEntityKey(
                    std::string_view engineName,
                    std::string_view orgId,
                    std::string_view entityValue,
                    bool isGlobalScope) const;
    EntityQueue& GetOrCreateEntityQueue(const std::string& key);
    void MarkEntityReady(const std::string& key, EntityQueue& eq);
    void CheckAndRunBackgroundOps();

    // Initialize per-worker engine clones from the master engine map.
    // Each clone shares Aerospike/crypto but has its own fresh cache.
    void InitializeWorkerEngines();

    // Helpers to reduce boilerplate in Process* methods
    IStorageEngine_Ptr ResolveEngine(const std::string& entityTypeName, const char* methodName);

    // Extracted helpers for readability (no behavior changes)
    void ReportTimingStats();
    void RecordUpdateLatency(uint64_t updateUs);
    bool SubmitEntityFetch(WorkItemPtr& item,
                           EntityQueue& eq,
                           FetchRequest req,
                           EntityQueue::OpCategory cat);
    FetchRequest BuildFetchRequest(IStorageEngine_Ptr engine,
                                   std::string_view entityTypeName,
                                   std::string_view orgId,
                                   std::string_view entityValue,
                                   int32_t crcForSharding,
                                   bool addToCacheWhenNotFound,
                                   boost::optional<bool> allowWriteToDB,
                                   std::string_view entityKey,
                                   EntityQueue& eq);

    // Re-insert serialized write entries into the concurrent write map after
    // a failed Submit to the write thread queue. Resets DataState to DataModified
    // so the entries will be re-snapshotted on the next cycle.
    void ReinsertFailedWriteEntries(const IStorageEngine_Ptr& engine,
                                    std::vector<SerializedWriteEntry>& entries);

    void WaitForWork();

    bool IsClonedFromMasterEngine(std::string_view name, const IStorageEngine_Ptr& worker) const;

    uint32_t m_workerIndex;
    uint32_t m_numWorkers;
    uint32_t m_entityQueueDepth;
    uint32_t m_numShardQueues;
    uint32_t m_maxUpdatesPerEntity{EntityQueue::DEFAULT_MAX_PENDING_UPDATES};
    uint32_t m_maxGetsPerEntity{EntityQueue::DEFAULT_MAX_PENDING_GETS};
    uint32_t m_maxExtendTTLsPerEntity{EntityQueue::DEFAULT_MAX_PENDING_EXTEND_TTLS};
    std::vector<std::unique_ptr<Queue>> m_shardQueues;
    uint32_t m_drainRoundRobin{0};  // Fair round-robin start for shard queue drain
    std::jthread m_thread;
    std::atomic<bool> m_running{false};

    // Condition variable for sleep/wake.
    // m_wakeFlag is atomic so Wake() can skip the mutex in the fast path
    // (flag already set means worker is awake or will see it).
    std::mutex m_wakeMutex;
    std::condition_variable m_wakeCv;
    std::atomic<bool> m_wakeFlag{false};

    const StorageEngineMap* m_masterEngines{nullptr};
    StorageEngineMap m_workerEngines;  // per-worker clones with own caches

    // Fetch thread pool for async DB reads — required for non-blocking worker operation
    FetchThreadPool* m_fetchPool{nullptr};

    // Lock-free MPSC queue for fetch completion notifications.
    // Pool threads push EntityQueue* pointers here after storing the result
    // in the EntityQueue's atomic fetchResult slot. The worker pops from this
    // to know exactly which entities have results, avoiding a full scan.
    // MPMC atomic_queue with SPSC=false (multiple pool threads may push).
    using CompletionNotifyQueue = atomic_queue::AtomicQueueB<EntityQueue*, std::allocator<EntityQueue*>, nullptr, true, false, false>;
    CompletionNotifyQueue m_completionNotifyQueue;

    // Fallback flag: set when the notification queue is full and try_push fails.
    // When set, the worker falls back to a full scan of m_entityQueues to find
    // any entity with a non-null fetchResult. This prevents entity softlock
    // when the notification queue can't accept another pointer.
    std::atomic<bool> m_hasPendingCompletions{false};

    // Write thread for async DB writes — required for non-blocking worker operation
    ShardWriteThread* m_writeThread{nullptr};

    // Pre-allocated work item pool — nullptr means fall back to delete
    ShardWorkItemPool* m_workItemPool{nullptr};

    // Per-entity-type EntityCache — each engine (entity type) gets its own
    // Per-entity operation queues — owned exclusively by this worker thread
    // (Instead of a simple std::unordered_map<std::string, EntityQueue>
    // we use a custom transparent hash to allow efficient lookup by entity key
    // without constructing temporary std::string objects.
    // Thanks to Sonarqube and Copilot)

    std::unordered_map<std::string, EntityQueue, StringHash, std::equal_to<>> m_entityQueues;

    // Ready entity queue: keys of entities that currently have runnable work
    // (pending ops and no fetch in flight).
    std::deque<std::string> m_readyEntities;

    // Background ops timer
    using Clock = std::chrono::steady_clock;
    Clock::time_point m_lastBackgroundOpsTime {Clock::now()};

    // Stats
    std::atomic<uint64_t> m_processedCount{0};
    std::atomic<uint64_t> m_droppedCount{0};
    std::atomic<uint64_t> m_coalescedCount{0};
    std::atomic<uint64_t> m_staleCount{0};

    // Per-type rejection counters (reset each reporting interval)
    uint64_t m_rejectedUpdates{0};
    uint64_t m_rejectedGets{0};
    uint64_t m_rejectedExtendTTLs{0};

    // Rate-limited warning for entity queue drops
    PeriodMonitor<> m_entityQueueDropWarning{std::chrono::milliseconds(5000)};

    // Adaptive spin configuration
    static constexpr uint32_t SPIN_COUNT = 16;
    static constexpr uint32_t YIELD_COUNT = 4;

    // Batch processing: drain up to this many items per round from SPSC queue
    static constexpr uint32_t BATCH_DRAIN_SIZE = 8;

    // Per-entity budget: max ops processed per entity per batch round.
    // Prevents a single hot entity from starving others.
    static constexpr uint32_t ENTITY_OPS_BUDGET = 8;

    // Timing instrumentation — tracks where worker time is spent
    struct WorkerTimingStats {
        uint64_t processingUs{0};   // Time in ProcessBatch (CPU-bound work)
        uint64_t idleWaitUs{0};     // Time in WaitForWork (condvar wait)
        uint64_t drainUs{0};        // Time draining MPMC queue
        uint64_t completionUs{0};   // Time signaling CompletionEvent (futex_wake)
        uint64_t batchCount{0};     // Number of batches processed
        uint64_t emptyPolls{0};     // Times queue was empty

        // Per-phase breakdown (within processing)
        uint64_t cacheGetUs{0};     // TryGetFromCache (includes crcBelongsToOwnServer, TmMetrics)
        uint64_t cacheUpdateUs{0};  // UpdateTheRecord (includes decrypt, apply journals, TmMetrics)
        uint64_t cacheCheckUs{0};   // IsEntityInCache
        uint64_t completionSigUs{0};// CompleteAndMaybeDelete (CAS + futex_wake)
        uint64_t queueWaitUs{0};    // Time from enqueue to worker pickup
        uint64_t queueWaitMaxUs{0}; // Max queue wait time
        uint64_t getCount{0};       // Number of ProcessGet calls
        uint64_t updateCount{0};    // Number of ProcessUpdate calls
        uint64_t cacheHits{0};      // Get cache hits
        uint64_t cacheMisses{0};    // Get cache misses (fetch dispatched)
        uint64_t fetchQueued{0};    // Items queued behind inflight fetch
        uint64_t fetchDropped{0};   // Items which failed to be queued behind inflight fetch (entity queue full)
        uint64_t fetchRequests{0};  // Fetch requests made
        uint64_t fetchRequestsFound{0};    // Fetch completed and found the entity
        uint64_t fetchRequestsNotFound{0}; // Fetch completed but did not find the entity

        // Tail latency tracking (max + threshold buckets for cacheUpd)
        uint64_t cacheUpdMaxUs{0};       // Max single update time
        uint64_t updOver1ms{0};          // Count of updates > 1ms
        uint64_t updOver2ms{0};          // Count of updates > 2ms
        uint64_t updOver5ms{0};          // Count of updates > 5ms
        uint64_t updOver10ms{0};         // Count of updates > 10ms

        Clock::time_point lastReport;

        void Reset(Clock::time_point now) {
            processingUs = idleWaitUs = drainUs = completionUs = 0;
            batchCount = emptyPolls = 0;
            cacheGetUs = cacheUpdateUs = cacheCheckUs = completionSigUs = 0;
            queueWaitUs = queueWaitMaxUs = 0;
            getCount = updateCount = cacheHits = cacheMisses = 0;
            fetchQueued = fetchDropped = 0;
            fetchRequests = fetchRequestsFound = fetchRequestsNotFound = 0;
            cacheUpdMaxUs = 0;
            updOver1ms = updOver2ms = updOver5ms = updOver10ms = 0;
            lastReport = now;
        }
    };
    WorkerTimingStats m_timing;

    // Peer workers for aggregate reporting (set by ShardDispatcher after all workers created)
    std::vector<ShardWorker*> m_peerWorkers;

    // Snapshot written at each report interval for aggregate consumption by worker 0
    WorkerSnapshot m_snapshot;
};
