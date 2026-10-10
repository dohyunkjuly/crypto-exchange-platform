package com.exchange.spot.settlement.kafka;

import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.spot.settlement.trade.BalanceEffectService;
import com.exchange.spot.settlement.trade.BalanceMutation;
import com.exchange.spot.settlement.trade.OrderEffectService;
import com.exchange.spot.settlement.trade.OrderEffectService.FillContribution;
import com.exchange.spot.settlement.trade.OrderEffectService.OrderFillContribution;
import com.exchange.spot.settlement.trade.OrderEffectService.TradeToRecord;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Applies a batch of {@code exchange.spot.events.v1} to orders and wallets. Why it groups, how
 * it parallelises, redelivery safety and failure handling: docs/reference/settlement-pipeline.md.
 *
 * <p>Only the step order lives here, because getting it wrong corrupts balances:
 * <ol>
 *   <li>Resolve every distinct symbol once, sequentially, before any parallel work -- so
 *       nothing reads and writes that map at once.
 *   <li>{@code OrderAmended}/{@code OrderAmendRejected}, before any fill: a fill that follows
 *       an amend to a larger size would be refused as an overfill against the old one. Safe
 *       for the fills that came BEFORE it too -- the engine only accepts a new total above
 *       what had filled -- and the release is sized from the event, never from the row.
 *   <li>{@link OrderEffectService#recordTrades}, alone: what it returns is what the
 *       balance work in step 3 is built from.
 *   <li>{@link OrderEffectService#applyFillsForOrder} and the trade-driven balance groups
 *       TOGETHER -- disjoint tables, so nothing serialises them.
 *   <li>{@code OrderRejected}/{@code OrderCanceled} last, after every fill has completed: they
 *       read an order's current state to compute the unlock, and their own unlock mutations do
 *       not exist until this step has run.
 * </ol>
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    /** Bounded, small: a real InnoDB deadlock resolves itself within a poll or two of
     *  competing transactions finishing, not dozens -- see {@link #withDeadlockRetry}. */
    private static final int MAX_DEADLOCK_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 25;

    private final SpotSymbolRepository spotSymbols;
    private final OrderEffectService orderEffects;
    private final BalanceEffectService balanceEffects;

    public EventConsumer(SpotSymbolRepository spotSymbols, OrderEffectService orderEffects,
                         BalanceEffectService balanceEffects) {
        this.spotSymbols = spotSymbols;
        this.orderEffects = orderEffects;
        this.balanceEffects = balanceEffects;
    }

    @KafkaListener(topics = Topics.SPOT_EVENTS)
    public void onEvents(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        List<Event> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                events.add(Event.parseFrom(record.value()));
            } catch (InvalidProtocolBufferException e) {
                log.error("skipping malformed event at partition {} offset {}",
                        record.partition(), record.offset(), e);
            }
        }

        try {
            Map<String, SpotSymbol> symbolCache = resolveSymbols(events);
            List<Event> settleable = settleable(events, symbolCache);

            dispatchGroups(groupByBalanceRow(applyAmends(settleable, symbolCache)));

            // Recording the trades is the ONLY order-side work the trade-driven balance
            // mutations wait on -- they are derived from the trades themselves, not from
            // any order's post-fill state.
            List<BalanceMutation> tradeMutations = recordTrades(settleable, symbolCache);

            // So this one wave runs each order's aggregate fill AND those balance groups
            // together rather than one phase after the other: fills touch only
            // spot_orders/spot_order_fill_log, balance groups touch only
            // wallet_balances/wallet_ledger_entries -- disjoint tables, no shared row,
            // nothing to serialize them against each other.
            List<Callable<List<BalanceMutation>>> wave = new ArrayList<>(
                    fillTasks(settleable, symbolCache));
            wave.addAll(groupTasks(groupByBalanceRow(tradeMutations)));
            runAll(wave);

            // Reject/cancel must see every fill already applied (they read an order's
            // CURRENT state to size the unlock), so they wait for the wave above, and
            // their own unlock mutations necessarily follow them.
            dispatchGroups(groupByBalanceRow(applyOrderStateEvents(settleable, symbolCache)));
        } catch (RuntimeException e) {
            log.error("failed to settle a batch of {} events; not acknowledging, will redeliver",
                    records.size(), e);
            return;
        }
        ack.acknowledge();
    }

    /** Sequential and cheap -- a batch is almost always one partition's worth of
     *  records, which is almost always one symbol, but nothing here assumes that.
     *  Done once, up front, so the map below is read-only for the rest of this
     *  method -- safe to share across the virtual threads dispatched later, unlike a
     *  plain {@code HashMap} being written to concurrently. */
    private Map<String, SpotSymbol> resolveSymbols(List<Event> events) {
        Set<String> codes = new LinkedHashSet<>();
        for (Event event : events) {
            codes.add(event.getSymbol());
        }
        Map<String, SpotSymbol> symbols = new HashMap<>();
        for (String code : codes) {
            spotSymbols.findBySymbol(code).ifPresentOrElse(symbol -> symbols.put(code, symbol),
                    () -> log.error("unknown symbol {} on the event stream; its events cannot be "
                            + "settled and are skipped", code));
        }
        return symbols;
    }

    /**
     * Drops what can never be settled, so it does not block the partition forever.
     *
     * <p>Retrying cannot fix either case: a market MySQL does not know has no assets to move
     * funds between, and a trade with one user on both sides cannot be recorded as a buyer and
     * a seller (no self-trade prevention mode allows one). Both are logged as errors -- in
     * production they mean something is badly wrong upstream -- but a poison event that stops
     * every other market on its partition is the worse failure.
     */
    private static List<Event> settleable(List<Event> events, Map<String, SpotSymbol> symbols) {
        List<Event> kept = new ArrayList<>(events.size());
        for (Event event : events) {
            if (!symbols.containsKey(event.getSymbol())) {
                continue;
            }
            if (event.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED
                    && event.getTradeExecuted().getTakerUserId() == event.getTradeExecuted().getMakerUserId()) {
                log.error("self-trade by user {} on {} (sequence {}) cannot be settled and is skipped",
                        event.getTradeExecuted().getTakerUserId(), event.getSymbol(), event.getSequence());
                continue;
            }
            kept.add(event);
        }
        return kept;
    }

    /** One virtual thread per distinct SYMBOL touched by a {@code TradeExecuted} in this
     *  batch, recording all of that symbol's trades as one aggregate {@link
     *  OrderEffectService#recordTrades} call -- almost always one task, one symbol, since
     *  a batch is almost always one partition's worth of records.
     *
     *  <p>Deliberately its own step, ahead of everything else: the balance mutations it
     *  returns are what the rest of the batch's balance work is built from, and they
     *  depend on the trades alone, never on any order's post-fill state. Getting them
     *  early is what lets the balance side start while the fills are still going. */
    private List<BalanceMutation> recordTrades(List<Event> events,
                                               Map<String, SpotSymbol> symbolCache) {
        Map<String, List<TradeToRecord>> tradesBySymbol = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.getPayloadCase() != Event.PayloadCase.TRADE_EXECUTED) {
                continue;
            }
            tradesBySymbol.computeIfAbsent(event.getSymbol(), key -> new ArrayList<>())
                    .add(new TradeToRecord(event.getTradeExecuted(), event.getSequence(),
                            microsToInstant(event.getTimestampUs())));
        }

        List<Callable<List<BalanceMutation>>> tasks = new ArrayList<>(tradesBySymbol.size());
        for (Map.Entry<String, List<TradeToRecord>> entry : tradesBySymbol.entrySet()) {
            SpotSymbol symbol = symbolCache.get(entry.getKey());
            List<TradeToRecord> trades = entry.getValue();
            tasks.add(() -> orderEffects.recordTrades(symbol, trades));
        }
        return runAll(tasks);
    }

    /** One task per distinct order touched by any {@code TradeExecuted} in this batch,
     *  applying that order's fills -- however many trades hit it -- as a single aggregate
     *  write. Grouping the contributions by {@code orderId} is cheap, pure, in-memory
     *  work ({@link OrderEffectService#deriveFillContributions} touches no database),
     *  done up front before anything is dispatched. Returns the tasks rather than running
     *  them, so the caller can run them in the same wave as the balance groups. */
    private List<Callable<List<BalanceMutation>>> fillTasks(List<Event> events,
                                                            Map<String, SpotSymbol> symbolCache) {
        Map<UUID, List<FillContribution>> fillsByOrder = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.getPayloadCase() != Event.PayloadCase.TRADE_EXECUTED) {
                continue;
            }
            for (OrderFillContribution ofc
                    : orderEffects.deriveFillContributions(event.getTradeExecuted())) {
                fillsByOrder.computeIfAbsent(ofc.orderId(), key -> new ArrayList<>())
                        .add(ofc.contribution());
            }
        }

        List<Callable<List<BalanceMutation>>> tasks = new ArrayList<>(fillsByOrder.size());
        for (Map.Entry<UUID, List<FillContribution>> entry : fillsByOrder.entrySet()) {
            UUID orderId = entry.getKey();
            List<FillContribution> contributions = entry.getValue();
            tasks.add(() -> {
                orderEffects.applyFillsForOrder(orderId, contributions);
                return List.of();
            });
        }
        return tasks;
    }

    /** Every mutation in one returned group shares a {@code (userId, assetId)} -- the
     *  whole point, since that is what lets {@code BalanceService#applyBatch} apply many
     *  of them to one balance row with one lock and one write.
     *
     *  <p>Grouping happens per wave rather than once across the whole batch, so a user
     *  who is touched by BOTH a trade and a reject/cancel in the same batch gets two
     *  transactions on that row instead of one. That costs a little aggregation in the
     *  mixed case, and buys the far larger win of not making every balance write in the
     *  batch wait behind every fill -- and the two are sequential in time either way, so
     *  it adds no contention. */
    private Collection<List<BalanceMutation>> groupByBalanceRow(List<BalanceMutation> mutations) {
        Map<GroupKey, List<BalanceMutation>> grouped = new LinkedHashMap<>();
        for (BalanceMutation mutation : mutations) {
            grouped.computeIfAbsent(new GroupKey(mutation.userId(), mutation.assetId()),
                    key -> new ArrayList<>()).add(mutation);
        }
        return grouped.values();
    }

    /** One task per {@code (userId, assetId)} group -- see this class's own javadoc for
     *  why running them together is safe by construction. Returns the tasks rather than
     *  running them, so they can share a wave with {@link #fillTasks}. */
    private List<Callable<List<BalanceMutation>>> groupTasks(
            Collection<List<BalanceMutation>> groups) {
        List<Callable<List<BalanceMutation>>> tasks = new ArrayList<>(groups.size());
        for (List<BalanceMutation> group : groups) {
            tasks.add(() -> {
                balanceEffects.applyGroup(group);
                return List.of();
            });
        }
        return tasks;
    }

    /** Dispatched together like {@link #applyOrderStateEvents}: an order has at most one
     *  amend in flight, so no two of these touch the same row. */
    private List<BalanceMutation> applyAmends(List<Event> events, Map<String, SpotSymbol> symbolCache) {
        List<Callable<List<BalanceMutation>>> tasks = new ArrayList<>();
        for (Event event : events) {
            SpotSymbol symbol = symbolCache.get(event.getSymbol());
            switch (event.getPayloadCase()) {
                case ORDER_AMENDED -> tasks.add(() -> orderEffects.applyAmend(symbol,
                        event.getOrderAmended(), Ulid.toUuid(event.getCommandId().toByteArray())));
                case ORDER_AMEND_REJECTED -> tasks.add(() -> orderEffects.applyAmendReject(symbol,
                        event.getOrderAmendRejected(), Ulid.toUuid(event.getCommandId().toByteArray())));
                default -> {
                }
            }
        }
        return runAll(tasks);
    }

    /** One virtual thread per {@code OrderRejected}/{@code OrderCanceled} event, called
     *  by {@code onEvents} only once every trade/fill above has FULLY completed -- these
     *  read an order's CURRENT state to compute how much remains to unlock, so none of
     *  them may run concurrently with a fill that could still be updating that same
     *  order. That constraint is about fills, though, not about each other: two
     *  different orders' own reject/cancel never touch the same row (each is a single
     *  order's own {@link OrderEffectService#applyReject}/{@link
     *  OrderEffectService#applyCancel} call), so -- same as {@link #fillTasks} -- they
     *  are dispatched together rather than one at a time. See this class's own javadoc
     *  for why a sequential loop here turned out to matter in practice, not just in
     *  theory. */
    private List<BalanceMutation> applyOrderStateEvents(
            List<Event> events, Map<String, SpotSymbol> symbolCache) {
        List<Callable<List<BalanceMutation>>> tasks = new ArrayList<>();
        for (Event event : events) {
            SpotSymbol symbol = symbolCache.get(event.getSymbol());
            switch (event.getPayloadCase()) {
                case ORDER_REJECTED -> {
                    var rejected = event.getOrderRejected();
                    tasks.add(() -> orderEffects.applyReject(symbol, rejected));
                }
                case ORDER_CANCELED -> {
                    var canceled = event.getOrderCanceled();
                    tasks.add(() -> orderEffects.applyCancel(symbol, canceled));
                }
                // TRADE_EXECUTED already handled by recordTrades/fillTasks. OrderService
                // already saves a new SpotOrder with the correct status (NEW, or
                // PENDING_TRIGGER for a conditional order) synchronously at placement,
                // before the command ever reaches the engine -- ORDER_ACCEPTED carries
                // nothing that still needs writing.
                // ORDER_AMENDED and ORDER_AMEND_REJECTED: applyAmends, before the fills.
                case TRADE_EXECUTED, ORDER_ACCEPTED, ORDER_AMENDED, ORDER_AMEND_REJECTED,
                     PAYLOAD_NOT_SET -> {
                }
            }
        }
        return runAll(tasks);
    }

    /** Runs {@link #groupTasks} on its own, for the balance work that has nothing left
     *  to share a wave with. Propagates the first failure encountered among them; the
     *  others' work already happened (each in its own transaction), which is fine --
     *  every one of them is a safe no-op to redo, same as the rest of this batch, once
     *  the whole thing is redelivered. */
    private void dispatchGroups(Collection<List<BalanceMutation>> groups) {
        runAll(groupTasks(groups));
    }

    private List<BalanceMutation> runAll(List<Callable<List<BalanceMutation>>> tasks) {
        List<BalanceMutation> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<BalanceMutation>>> futures = new ArrayList<>(tasks.size());
            for (Callable<List<BalanceMutation>> task : tasks) {
                futures.add(executor.submit(withDeadlockRetry(task)));
            }
            for (Future<List<BalanceMutation>> future : futures) {
                try {
                    results.addAll(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("settlement task failed", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted settling a batch", e);
                }
            }
        }
        return results;
    }

    /**
     * Retries {@code task} a bounded number of times, with jittered backoff, if it
     * fails on {@link CannotAcquireLockException} -- Spring's translation of an InnoDB
     * lock-wait timeout or deadlock, both purely transient. Any other exception is not
     * retried; it propagates immediately, exactly as before this existed.
     *
     * <p>Exists because of one concrete failure this design can hit: many independent
     * trades landing in one batch means many DIFFERENT users touching {@code
     * wallet_balances} for the first time at once (see {@code BalanceService}'s
     * first-touch-creates-a-row comment), and MySQL can genuinely deadlock two
     * concurrent INSERTs into a table with a unique index even though they're for
     * entirely different rows -- confirmed against real infra, not assumed. Without
     * this, one such deadlock aborted and redelivered the ENTIRE batch over one
     * failing group. Retrying just that one task is far cheaper and exactly as safe:
     * a deadlock always rolls back the whole transaction, so retrying starts clean,
     * and every task dispatched here is already independently idempotent by design
     * (this class's own javadoc) -- a retry is no different from the redelivery this
     * whole system is already built to tolerate.
     */
    private static Callable<List<BalanceMutation>> withDeadlockRetry(
            Callable<List<BalanceMutation>> task) {
        return () -> {
            int attempt = 0;
            while (true) {
                try {
                    return task.call();
                } catch (CannotAcquireLockException e) {
                    attempt++;
                    if (attempt > MAX_DEADLOCK_RETRIES) {
                        throw e;
                    }
                    long delayMs = RETRY_BASE_DELAY_MS * (1L << (attempt - 1))
                            + ThreadLocalRandom.current().nextLong(RETRY_BASE_DELAY_MS);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        };
    }

    private static Instant microsToInstant(long timestampUs) {
        return Instant.EPOCH.plus(timestampUs, ChronoUnit.MICROS);
    }

    private record GroupKey(Long userId, Long assetId) {
    }
}
