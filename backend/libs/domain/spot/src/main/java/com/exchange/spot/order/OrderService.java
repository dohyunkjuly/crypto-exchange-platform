package com.exchange.spot.order;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.CancelScope;
import com.exchange.common.kafka.Topics;
import com.exchange.common.id.Ulid;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.fee.FeeRates;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimits;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimitsRepository;
import com.exchange.spot.limit.override.SpotUserOrderLimitOverride;
import com.exchange.spot.limit.override.SpotUserOrderLimitOverrideRepository;
import com.exchange.spot.limit.symbol.SpotSymbolUserOrderLimitOverride;
import com.exchange.spot.limit.symbol.SpotSymbolUserOrderLimitOverrideRepository;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.spot.order.exception.AlgoOrderLimitExceededException;
import com.exchange.spot.order.exception.AmendPendingException;
import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.FieldNotAmendableException;
import com.exchange.spot.order.exception.FokWithExpireBothNotSupportedException;
import com.exchange.spot.order.exception.InvalidPriceException;
import com.exchange.spot.order.exception.InvalidQuantityException;
import com.exchange.spot.order.exception.InvalidStopPriceException;
import com.exchange.spot.order.exception.InvalidTrailingDeltaException;
import com.exchange.spot.order.exception.MarketOrdersDisabledException;
import com.exchange.spot.order.exception.NothingToAmendException;
import com.exchange.spot.order.exception.NotionalOutOfRangeException;
import com.exchange.spot.order.exception.OrderNotAmendableException;
import com.exchange.spot.order.exception.OrderNotCancelableException;
import com.exchange.spot.order.exception.OrderNotFoundException;
import com.exchange.spot.order.exception.OrderLimitExceededException;
import com.exchange.spot.order.exception.QuoteOrderQtyNotSupportedException;
import com.exchange.spot.order.exception.QuoteOrderQtyRequiredException;
import com.exchange.spot.order.exception.StopOrdersDisabledException;
import com.exchange.spot.order.exception.SymbolNotFoundException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.spot.order.exception.TimeInForceNotSupportedException;
import com.exchange.spot.outbox.CommandOutboxService;
import com.exchange.spot.outbox.SpotCommandOutbox;
import com.exchange.spot.symbol.CommandPartitions;
import com.exchange.spot.symbol.EnginePresence;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates, resolves fees, locks funds and publishes a spot order. Shared by web-bff and
 * spot-open-api, so it takes and returns plain values rather than either app's DTOs and throws
 * code-less exceptions ({@code com.exchange.spot.order.exception}) that each app's own
 * {@code OrderExceptionAdvice} maps -- see AGENTS.md's "does more than one app need it?".
 *
 * <p><b>Does not check KYC or 2FA.</b> That gate is at each app's edge, because both apps
 * already load the user to authenticate and checking here would cost a read per order. A new
 * caller must apply it itself: {@code EligibilityService.requireCanTrade}. Placement only --
 * key creation stays gated in the domain, since minting a credential is not a hot path.
 *
 * <p>See docs/reference/order-types.md for the sizing and validation table this implements.
 */
@Service
public class OrderService {

    private static final List<OrderStatus> OPEN_STATUSES = List.of(
            OrderStatus.PENDING_TRIGGER, OrderStatus.NEW,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_CANCEL);

    /** Open, minus PENDING_CANCEL: its cancel is already on its way. */
    private static final List<OrderStatus> CANCELABLE_STATUSES = List.of(
            OrderStatus.PENDING_TRIGGER, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED);

    private static final List<OrderType> CONDITIONAL_TYPES = List.of(
            OrderType.STOP_LOSS, OrderType.STOP_LOSS_LIMIT,
            OrderType.TAKE_PROFIT, OrderType.TAKE_PROFIT_LIMIT);

    private static final List<OrderType> LIMIT_PRICED_TYPES = List.of(
            OrderType.LIMIT, OrderType.LIMIT_MAKER,
            OrderType.STOP_LOSS_LIMIT, OrderType.TAKE_PROFIT_LIMIT);

    private static final List<OrderType> MARKET_FIRING_TYPES = List.of(
            OrderType.MARKET, OrderType.STOP_LOSS, OrderType.TAKE_PROFIT);

    private static final int MIN_TRAILING_DELTA_BPS = 10;
    private static final int MAX_TRAILING_DELTA_BPS = 2_000;

    private static final int KAFKA_PUBLISH_TIMEOUT_SECONDS = 5;

    private final SpotOrderRepository spotOrders;
    private final SpotSymbolRepository spotSymbols;
    private final SpotExchangeOrderLimitsRepository exchangeLimits;
    private final SpotUserOrderLimitOverrideRepository userLimitOverrides;
    private final SpotSymbolUserOrderLimitOverrideRepository symbolUserLimitOverrides;
    private final FeeQueryService feeQuery;
    private final BalanceService balanceService;
    private final KafkaTemplate<String, byte[]> kafka;
    private final CommandOutboxService outbox;
    private final CommandPartitions commandPartitions;
    private final EnginePresence enginePresence;
    /** Empty in an app with nothing to say about sub-accounts' orders. */
    private final Optional<SubAccountOrderHook> subAccountHook;

    /**
     * Placing, amending and cancelling are each a transaction that ENDS, and then a send.
     * {@code @Transactional} would not do: it keeps the database connection until the method
     * returns, so the send -- and the marking of the outbox row, which needs a connection of
     * its own -- would happen while still holding one. See {@link CommandOutboxService}.
     */
    private final TransactionTemplate transaction;

    public OrderService(SpotOrderRepository spotOrders, SpotSymbolRepository spotSymbols,
                        SpotExchangeOrderLimitsRepository exchangeLimits,
                        SpotUserOrderLimitOverrideRepository userLimitOverrides,
                        SpotSymbolUserOrderLimitOverrideRepository symbolUserLimitOverrides,
                        FeeQueryService feeQuery,
                        BalanceService balanceService, KafkaTemplate<String, byte[]> kafka,
                        CommandOutboxService outbox, CommandPartitions commandPartitions, EnginePresence enginePresence,
                        Optional<SubAccountOrderHook> subAccountHook,
                        PlatformTransactionManager transactionManager) {
        this.spotOrders = spotOrders;
        this.spotSymbols = spotSymbols;
        this.exchangeLimits = exchangeLimits;
        this.userLimitOverrides = userLimitOverrides;
        this.symbolUserLimitOverrides = symbolUserLimitOverrides;
        this.feeQuery = feeQuery;
        this.balanceService = balanceService;
        this.kafka = kafka;
        this.outbox = outbox;
        this.subAccountHook = subAccountHook;
        this.commandPartitions = commandPartitions;
        this.enginePresence = enginePresence;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** What a transaction decided to send, carried out of it so the sending happens after it. */
    private record Outgoing(SpotCommandOutbox row, SpotSymbol market,
                            com.exchange.contracts.spot.v1.Command direct) {
        static final Outgoing NOTHING = new Outgoing(null, null, null);

        /** Through the outbox: committed with the order, sent by the publisher after the 202. */
        static Outgoing queued(SpotCommandOutbox row) {
            return new Outgoing(row, null, null);
        }

        /** Straight to Kafka: a command that wrote nothing, so there is nothing to keep in step. */
        static Outgoing direct(SpotSymbol market, com.exchange.contracts.spot.v1.Command command) {
            return new Outgoing(null, market, command);
        }
    }

    private record Placed(OrderDetail order, Outgoing outgoing) {
    }

    private record CancelledAll(int count, List<Outgoing> outgoing) {
    }

    private void send(Outgoing outgoing) {
        if (outgoing.direct() != null) {
            publishDirect(outgoing.market(), outgoing.direct());
        } else {
            outbox.handOff(outgoing.row());
        }
    }

    /**
     * @return the placed (or, on an idempotent replay, the original) order, as it was committed:
     *         a caller that answers with the order must not read it back, which was a second
     *         transaction on every order placed
     * @throws SymbolNotFoundException no symbol matches {@code symbol}
     * @throws SymbolNotTradingException the symbol isn't accepting new orders
     * @throws MarketOrdersDisabledException a MARKET order on a symbol that disallows them
     * @throws StopOrdersDisabledException a conditional order on a symbol that disallows them
     * @throws TimeInForceNotSupportedException GTD, or a missing time in force
     * @throws InvalidPriceException price missing or off-tick where required
     * @throws InvalidStopPriceException stop price missing (and not trailing) or off-tick
     * @throws InvalidQuantityException quantity/quoteOrderQty missing, off-step, or out of range
     * @throws QuoteOrderQtyRequiredException a market-firing BUY without quoteOrderQty
     * @throws QuoteOrderQtyNotSupportedException quoteOrderQty on a SELL or a fixed-price order
     * @throws InvalidTrailingDeltaException trailingDeltaBps out of bounds or misplaced
     * @throws FokWithExpireBothNotSupportedException FOK with EXPIRE_BOTH
     * @throws NotionalOutOfRangeException price*quantity outside the symbol's allowed range
     * @throws OrderLimitExceededException the account or symbol open-order cap is reached
     * @throws AlgoOrderLimitExceededException the account or symbol open-conditional-order cap
     *         is reached
     * @throws ManualOrderNotAllowedException a sub-account that is not traded by hand
     * @throws MarketNotAllowedException a sub-account that may not trade this market
     */
    public OrderDetail placeOrder(Long userId, String symbol, Side side, OrderType orderType,
                           TimeInForce requestedTimeInForce, String priceDecimal,
                           String stopPriceDecimal, String quantityDecimal,
                           String quoteOrderQtyDecimal, String clientOrderId,
                           com.exchange.contracts.spot.v1.SelfTradePreventionMode
                                   selfTradePreventionMode,
                           Integer trailingDeltaBps) {
        return placeOrder(userId, null, symbol, side, orderType, requestedTimeInForce, priceDecimal,
                stopPriceDecimal, quantityDecimal, quoteOrderQtyDecimal, clientOrderId, selfTradePreventionMode,
                trailingDeltaBps);
    }

    /**
     * As above, for an account that may be a sub-account.
     *
     * @param masterId the account's master, whose fee a sub pays in full -- override or tier
     *                 ({@code FeeQueryService}); null for a master
     */
    public OrderDetail placeOrder(Long userId, Long masterId, String symbol, Side side, OrderType orderType,
                           TimeInForce requestedTimeInForce, String priceDecimal,
                           String stopPriceDecimal, String quantityDecimal,
                           String quoteOrderQtyDecimal, String clientOrderId,
                           com.exchange.contracts.spot.v1.SelfTradePreventionMode
                                   selfTradePreventionMode,
                           Integer trailingDeltaBps) {
        Placed placed = transaction.execute(status -> placeInTransaction(userId, masterId, symbol, side, orderType,
                requestedTimeInForce, priceDecimal, stopPriceDecimal, quantityDecimal,
                quoteOrderQtyDecimal, clientOrderId, selfTradePreventionMode, trailingDeltaBps, true));
        send(placed.outgoing());
        return placed.order();
    }

    /**
     * The copier's door (docs/reference/copy-trading.md): an order in a copy account, which
     * {@link SubAccountOrderHook} refuses to everyone else, so it does not pass the hook.
     *
     * <p>{@code alongside} runs in the placing transaction, after the order and its command are
     * written and before anything is sent: the copier's own record of the copy commits with the
     * order or not at all, which is what makes a copy happen once. It must not start a
     * transaction of its own or send anything.
     *
     * @param priceDecimal null for a market order
     * @throws RuntimeException as {@link #placeOrder}, and whatever {@code alongside} throws:
     *         either way nothing is written and nothing is sent
     */
    public OrderDetail placeCopyOrder(Long userId, Long masterId, String symbol, Side side, OrderType orderType,
                                      TimeInForce timeInForce, String priceDecimal, String quantityDecimal,
                                      Consumer<OrderDetail> alongside) {
        Placed placed = transaction.execute(status -> {
            Placed made = placeInTransaction(userId, masterId, symbol, side, orderType, timeInForce,
                    priceDecimal, null, quantityDecimal, null, null, null, null, false);
            alongside.accept(made.order());
            return made;
        });
        send(placed.outgoing());
        return placed.order();
    }

    private Placed placeInTransaction(Long userId, Long masterId, String symbol, Side side, OrderType orderType,
                                      TimeInForce requestedTimeInForce, String priceDecimal,
                                      String stopPriceDecimal, String quantityDecimal,
                                      String quoteOrderQtyDecimal, String clientOrderId,
                                      com.exchange.contracts.spot.v1.SelfTradePreventionMode
                                              selfTradePreventionMode,
                                      Integer trailingDeltaBps, boolean hooked) {
        if (clientOrderId != null) {
            var existing = spotOrders.findByUserIdAndClientOrderId(userId, clientOrderId);
            if (existing.isPresent()) {
                String placedOn = spotSymbols.findById(existing.get().getSymbolId())
                        .map(SpotSymbol::getSymbol).orElse(null);
                return new Placed(new OrderDetail(existing.get(), placedOn), Outgoing.NOTHING);
            }
        }

        SpotSymbol spotSymbol = spotSymbols.findBySymbol(symbol)
                .orElseThrow(() -> new SymbolNotFoundException(symbol));
        if (!spotSymbol.acceptsNewOrders()) {
            throw new SymbolNotTradingException();
        }
        // Before anything locks: an order that can never be published must not hold funds.
        commandPartitions.requireExists(spotSymbol);
        requireEngine(spotSymbol);

        boolean isConditional = CONDITIONAL_TYPES.contains(orderType);
        boolean isMarketFiring = MARKET_FIRING_TYPES.contains(orderType);
        boolean isBuy = side == Side.BUY;

        if (orderType == OrderType.MARKET && !spotSymbol.isMarketOrdersEnabled()) {
            throw new MarketOrdersDisabledException();
        }
        if (isConditional && !spotSymbol.isStopOrdersEnabled()) {
            throw new StopOrdersDisabledException();
        }
        if (requestedTimeInForce == TimeInForce.GTD) {
            throw new TimeInForceNotSupportedException("Unsupported time in force");
        }
        if (orderType != OrderType.MARKET && requestedTimeInForce == null) {
            throw new TimeInForceNotSupportedException("time in force is required");
        }
        if (requestedTimeInForce == TimeInForce.FOK && selfTradePreventionMode
                == com.exchange.contracts.spot.v1.SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH) {
            throw new FokWithExpireBothNotSupportedException();
        }
        // MARKET is always IOC internally (it fills instantly or not at all) -- the
        // engine enforces this by rejecting one that carries a time in force at all,
        // so the persisted row and the published command both use this instead of
        // whatever (nothing) the request carried.
        TimeInForce timeInForce = orderType == OrderType.MARKET
                ? TimeInForce.IOC : requestedTimeInForce;

        if (trailingDeltaBps != null && (!isConditional
                || trailingDeltaBps < MIN_TRAILING_DELTA_BPS
                || trailingDeltaBps > MAX_TRAILING_DELTA_BPS)) {
            throw new InvalidTrailingDeltaException();
        }
        boolean isTrailing = trailingDeltaBps != null;

        Long price = null;
        if (LIMIT_PRICED_TYPES.contains(orderType)) {
            if (priceDecimal == null) {
                throw new InvalidPriceException("price is required for this order type");
            }
            price = parseMoney(priceDecimal,
                    () -> new InvalidPriceException("price must be a valid decimal"));
            if (!spotSymbol.isPriceOnTick(price)) {
                throw new InvalidPriceException("price is not on the symbol's tick size");
            }
        }

        Long stopPrice = null;
        if (isConditional) {
            if (stopPriceDecimal != null) {
                stopPrice = parseMoney(stopPriceDecimal,
                        () -> new InvalidStopPriceException("stopPrice must be a valid decimal"));
                if (!spotSymbol.isPriceOnTick(stopPrice)) {
                    throw new InvalidStopPriceException(
                            "stopPrice is not on the symbol's tick size");
                }
            } else if (!isTrailing) {
                throw new InvalidStopPriceException(
                        "stopPrice is required unless trailingDeltaBps is set");
            }
        }

        long quantity = 0;
        Long quoteOrderQty = null;
        if (isMarketFiring) {
            if (isBuy) {
                if (quoteOrderQtyDecimal == null) {
                    throw new QuoteOrderQtyRequiredException();
                }
                if (quantityDecimal != null) {
                    throw new QuoteOrderQtyNotSupportedException();
                }
                quoteOrderQty = parseMoney(quoteOrderQtyDecimal, () -> new InvalidQuantityException(
                        "quoteOrderQty must be a valid decimal"));
                if (!spotSymbol.isValidQuoteQuantity(quoteOrderQty)) {
                    throw new InvalidQuantityException("quoteOrderQty is out of range");
                }
            } else {
                if (quoteOrderQtyDecimal != null) {
                    throw new QuoteOrderQtyNotSupportedException();
                }
                if (quantityDecimal == null) {
                    throw new InvalidQuantityException("quantity is required");
                }
                quantity = parseMoney(quantityDecimal,
                        () -> new InvalidQuantityException("quantity must be a valid decimal"));
                if (!spotSymbol.isValidQuantity(true, quantity)) {
                    throw new InvalidQuantityException(
                            "quantity is out of range or off the step size");
                }
            }
        } else {
            if (quoteOrderQtyDecimal != null) {
                throw new QuoteOrderQtyNotSupportedException();
            }
            if (quantityDecimal == null) {
                throw new InvalidQuantityException("quantity is required");
            }
            quantity = parseMoney(quantityDecimal,
                    () -> new InvalidQuantityException("quantity must be a valid decimal"));
            if (!spotSymbol.isValidQuantity(false, quantity)) {
                throw new InvalidQuantityException(
                        "quantity is out of range or off the step size");
            }
            if (!spotSymbol.meetsNotional(price, quantity)) {
                throw new NotionalOutOfRangeException();
            }
        }

        checkOrderLimits(userId, spotSymbol, isConditional);

        FeeRates feeRates = resolveFees(userId, masterId);
        UUID orderId = Ulid.randomUuid();

        Long lockAssetId = isBuy ? spotSymbol.getQuoteAssetId() : spotSymbol.getBaseAssetId();
        long lockAmount = isBuy
                ? (quoteOrderQty != null ? quoteOrderQty : spotSymbol.notionalOf(price, quantity))
                : quantity;
        if (hooked && masterId != null && subAccountHook.isPresent()) {
            subAccountHook.get().beforeLock(orderId, userId, spotSymbol, side, lockAmount, quantity);
        }
        balanceService.lock(userId, lockAssetId, lockAmount, ReferenceType.ORDER,
                orderId.toString(), "order-lock:" + orderId);

        SpotOrder order = new SpotOrder(orderId, userId, spotSymbol.getId(), side, orderType,
                timeInForce, price, stopPrice, quantity, quoteOrderQty,
                feeRates.makerBps(), feeRates.takerBps(), clientOrderId, trailingDeltaBps);
        if (isConditional) {
            order.setStatus(OrderStatus.PENDING_TRIGGER);
        }
        spotOrders.save(order);

        // With the row and the lock, not after them: a command Kafka has for an order this
        // transaction rolled back is the engine trading on funds nobody holds. CommandOutboxService.
        return new Placed(new OrderDetail(order, spotSymbol.getSymbol()),
                Outgoing.queued(outbox.enqueue(spotSymbol, orderId, userId,
                        placeCommand(orderId, spotSymbol.getSymbol(), userId, side, orderType,
                                timeInForce, price, stopPrice, quantity, quoteOrderQty, feeRates,
                                trailingDeltaBps, clientOrderId, selfTradePreventionMode))));
    }

    /**
     * Asks the engine to cancel an order. Writes nothing, unless the order's own place or
     * amend has not reached the engine yet -- then the cancel is queued behind it in the
     * outbox rather than sent past it, which the engine would answer with silence.
     *
     * <p>Unlike {@link #placeOrder}, this deliberately does <b>not</b> touch the row or unlock
     * funds:
     *
     * <ol>
     *   <li>A cancel is a <i>request</i>. Whether it beats an in-flight fill is decided by the
     *       engine, on the partition, in command order. Writing CANCELED here would assert a
     *       result this service does not know; {@code spot-settlement} writes it when the
     *       {@code OrderCanceled} event says so, unlocking exactly what was still resting.
     *   <li>It would corrupt concurrent fills. Fills go through
     *       {@link SpotOrderRepository#applyFillAtomically}, a bulk UPDATE that bumps
     *       {@code version} -- loading and saving here would either throw an optimistic-lock
     *       failure on an ordinary race or write back a stale {@code filledQuantity}.
     * </ol>
     *
     * @throws OrderNotFoundException      no such order, or it belongs to someone else
     * @throws OrderNotCancelableException already in a terminal state
     */
    public void cancelOrder(Long userId, UUID orderId) {
        send(transaction.execute(status -> cancelInTransaction(userId, orderId)));
    }

    private Outgoing cancelInTransaction(Long userId, UUID orderId) {
        // The ownership check lives HERE and cannot be skipped: the engine's
        // cancelOrder cancels by order id alone and never compares user_id against the
        // resting order's owner, so a command published without this check would let any
        // authenticated user cancel anybody's order.
        SpotOrder order = spotOrders.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus().isTerminal()) {
            throw new OrderNotCancelableException(order.getStatus());
        }

        SpotSymbol spotSymbol = spotSymbols.findById(order.getSymbolId())
                .orElseThrow(() -> new SymbolNotFoundException(String.valueOf(order.getSymbolId())));

        com.exchange.contracts.spot.v1.Command command =
                cancelCommand(orderId, spotSymbol.getSymbol(), userId, order.getClientOrderId());
        return outbox.hasPendingFor(orderId)
                ? Outgoing.queued(outbox.enqueue(spotSymbol, orderId, userId, command))
                : Outgoing.direct(spotSymbol, command);
    }

    /**
     * No engine is running this market's partition: refuse now rather than queue an order
     * nobody will match, with its funds locked, until one appears. Never asked of a cancel --
     * a cancel waiting in Kafka is exactly what the trader wants.
     */
    private void requireEngine(SpotSymbol spotSymbol) {
        if (!enginePresence.isLive(spotSymbol.getCommandPartition())) {
            throw new EngineUnavailableException();
        }
    }

    /**
     * Changes an open order's price, stop price or size; null keeps a value. Answers before
     * the engine has decided anything: the outcome is an OrderAmended or OrderAmendRejected
     * event. Which fields an order takes is the table in docs/reference/order-types.md.
     *
     * <p>Funds are never short. If the new terms could need more locked, that much is locked
     * here, first -- sized for the worst fill that can land before the engine sees the amend
     * ({@link OrderLock#additionalFor}). Settlement releases whatever turns out to be surplus,
     * and all of it if the engine refuses. A decrease is released by settlement alone, once
     * the engine confirms.
     *
     * @param quantityDecimal the new TOTAL size, filled part included
     * @throws OrderNotFoundException      no such order, or it belongs to someone else
     * @throws OrderNotAmendableException  terminal, a market order, or IOC/FOK
     * @throws FieldNotAmendableException  a field this order cannot change
     * @throws NothingToAmendException     nothing sent, or nothing different
     * @throws AmendPendingException       an earlier amend has not been settled yet
     */
    public void amendOrder(Long userId, UUID orderId, String priceDecimal, String stopPriceDecimal,
                           String quantityDecimal, String quoteOrderQtyDecimal) {
        send(transaction.execute(status -> amendInTransaction(userId, orderId, priceDecimal,
                stopPriceDecimal, quantityDecimal, quoteOrderQtyDecimal)));
    }

    private Outgoing amendInTransaction(Long userId, UUID orderId, String priceDecimal,
                                        String stopPriceDecimal, String quantityDecimal,
                                        String quoteOrderQtyDecimal) {
        // Same reason as cancelOrder: the engine trusts the user_id it is sent.
        SpotOrder order = spotOrders.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!CANCELABLE_STATUSES.contains(order.getStatus())) {
            throw new OrderNotAmendableException("order is " + order.getStatus());
        }
        boolean parked = order.getStatus() == OrderStatus.PENDING_TRIGGER;
        if (!parked && (order.getOrderType() == OrderType.MARKET
                || order.getTimeInForce() != TimeInForce.GTC)) {
            throw new OrderNotAmendableException("only a resting GTC order or a parked one can be amended");
        }
        SpotSymbol spotSymbol = spotSymbols.findById(order.getSymbolId())
                .orElseThrow(() -> new SymbolNotFoundException(String.valueOf(order.getSymbolId())));
        // An amend can cross the book and trade, so a market closed to new orders refuses it too;
        // only a cancel is never refused.
        if (!spotSymbol.acceptsNewOrders()) {
            throw new SymbolNotTradingException();
        }
        requireEngine(spotSymbol);

        boolean quoteSized = order.getQuoteOrderQty() != null;
        if (order.getTrailingDeltaBps() != null && (priceDecimal != null || stopPriceDecimal != null)) {
            throw new FieldNotAmendableException(priceDecimal != null ? "price" : "stopPrice",
                    "a trailing order takes a new size only");
        }
        if (stopPriceDecimal != null && !parked) {
            throw new FieldNotAmendableException("stopPrice", "the order has already triggered");
        }
        if (priceDecimal != null && order.getPrice() == null) {
            throw new FieldNotAmendableException("price", "the order fires at the market price");
        }
        if (quantityDecimal != null && quoteSized) {
            throw new FieldNotAmendableException("quantity", "the order is sized by quoteOrderQty");
        }
        if (quoteOrderQtyDecimal != null && !quoteSized) {
            throw new FieldNotAmendableException("quoteOrderQty", "the order is sized by quantity");
        }

        Long newPrice = changed(priceDecimal, order.getPrice(),
                () -> new InvalidPriceException("price must be a valid decimal"));
        Long newStopPrice = changed(stopPriceDecimal, order.getStopPrice(),
                () -> new InvalidStopPriceException("stopPrice must be a valid decimal"));
        Long newQuantity = changed(quantityDecimal, order.getQuantity(),
                () -> new InvalidQuantityException("quantity must be a valid decimal"));
        Long newQuoteQty = changed(quoteOrderQtyDecimal, order.getQuoteOrderQty(),
                () -> new InvalidQuantityException("quoteOrderQty must be a valid decimal"));
        if (newPrice == null && newStopPrice == null && newQuantity == null && newQuoteQty == null) {
            throw new NothingToAmendException();
        }

        if (newPrice != null && !spotSymbol.isPriceOnTick(newPrice)) {
            throw new InvalidPriceException("price is not on the symbol's tick size");
        }
        if (newStopPrice != null && !spotSymbol.isPriceOnTick(newStopPrice)) {
            throw new InvalidStopPriceException("stopPrice is not on the symbol's tick size");
        }
        if (newQuoteQty != null && (!spotSymbol.isValidQuoteQuantity(newQuoteQty)
                || newQuoteQty <= order.getCumulativeQuoteQuantity())) {
            throw new InvalidQuantityException("quoteOrderQty is out of range");
        }
        boolean marketFiring = order.getPrice() == null;
        if (newQuantity != null && (!spotSymbol.isValidQuantity(marketFiring, newQuantity)
                || newQuantity <= order.getFilledQuantity())) {
            throw new InvalidQuantityException(
                    "quantity is out of range, off the step size, or not above what has filled");
        }
        Long price = newPrice != null ? newPrice : order.getPrice();
        long quantity = newQuantity != null ? newQuantity : order.getQuantity();
        if (price != null && !spotSymbol.meetsNotional(price, quantity)) {
            throw new NotionalOutOfRangeException();
        }

        if (spotOrders.claimAmend(orderId, CANCELABLE_STATUSES) == 0) {
            // Lost a race since the read above: to another amend, or to a fill or a cancel.
            throw order.isAmendPending() || spotOrders.isAmendPending(orderId)
                    ? new AmendPendingException()
                    : new OrderNotAmendableException("order is no longer open");
        }

        long oldTotal = quoteSized ? order.getQuoteOrderQty() : order.getQuantity();
        long newTotal = quoteSized ? (newQuoteQty != null ? newQuoteQty : oldTotal) : quantity;
        long filled = quoteSized ? order.getCumulativeQuoteQuantity() : order.getFilledQuantity();
        long additional = OrderLock.additionalFor(spotSymbol, order, price, oldTotal, newTotal, filled);
        UUID commandId = Ulid.randomUuid();
        if (additional > 0) {
            balanceService.lock(userId, OrderLock.assetId(spotSymbol, order), additional,
                    ReferenceType.ORDER, orderId.toString(), "order-amend-lock:" + commandId);
        }

        com.exchange.contracts.spot.v1.AmendOrder.Builder amend =
                com.exchange.contracts.spot.v1.AmendOrder.newBuilder()
                        .setOrderId(ByteString.copyFrom(Ulid.toBytes(orderId)))
                        .setUserId(userId)
                        .setAdditionalLocked(additional);
        if (order.getClientOrderId() != null) {
            amend.setClientOrderId(order.getClientOrderId());
        }
        if (newPrice != null) {
            amend.setNewPrice(newPrice);
        }
        if (newStopPrice != null) {
            amend.setNewStopPrice(newStopPrice);
        }
        if (newQuantity != null) {
            amend.setNewQuantity(newQuantity);
        }
        if (newQuoteQty != null) {
            amend.setNewQuoteOrderQty(newQuoteQty);
        }
        // With the claim and the extra lock, as a place goes with its row: an amend Kafka has
        // that this transaction rolled back would have settlement release a lock never taken.
        return Outgoing.queued(outbox.enqueue(spotSymbol, orderId, userId,
                com.exchange.contracts.spot.v1.Command.newBuilder()
                        .setCommandId(ByteString.copyFrom(Ulid.toBytes(commandId)))
                        .setSymbol(spotSymbol.getSymbol())
                        .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                        .setAmendOrder(amend)
                        .build()));
    }

    /** @throws OrderNotFoundException no order of this user carries that id */
    public void amendOrderByClientId(Long userId, String clientOrderId, String priceDecimal,
                                     String stopPriceDecimal, String quantityDecimal,
                                     String quoteOrderQtyDecimal) {
        send(transaction.execute(status -> amendInTransaction(userId, idOf(userId, clientOrderId),
                priceDecimal, stopPriceDecimal, quantityDecimal, quoteOrderQtyDecimal)));
    }

    private UUID idOf(Long userId, String clientOrderId) {
        return spotOrders.findByUserIdAndClientOrderId(userId, clientOrderId)
                .orElseThrow(() -> new OrderNotFoundException(clientOrderId)).getId();
    }

    /** The parsed value, or null if none was sent or it is what the order already has. */
    private Long changed(String decimal, Long current, Supplier<RuntimeException> onInvalid) {
        if (decimal == null) {
            return null;
        }
        long value = parseMoney(decimal, onInvalid);
        return current != null && current == value ? null : value;
    }

    /** By the client's own id: after a timed-out placement that is the only id a bot holds.
     *  Scoped to the user by the lookup itself, so one client's id can never reach another's order.
     *  @throws OrderNotFoundException no order of this user carries that id */
    public void cancelOrderByClientId(Long userId, String clientOrderId) {
        send(transaction.execute(status -> cancelInTransaction(userId, idOf(userId, clientOrderId))));
    }

    /**
     * Cancels this user's open orders matching every filter given; null means any, and
     * {@code scope} picks the book, the trigger book or both. Returns how many matched.
     *
     * <p>One {@code CancelAllOrders} per market, carrying the filters, so the engine decides
     * what matches when the command arrives -- which also catches an order placed after this
     * read. The rows read here only say which markets to send to, and how many to report.
     *
     * <p>So the count is what was open at this moment, and the engine may cancel more than
     * that; the order stream is what tells the user which orders actually went. It is also
     * why a market where the read found nothing gets no command: an order placed there
     * during this call survives, and the caller repeats the request. Ownership needs no
     * per-order check -- {@code user_id} is on the command, and the engine only ever touches
     * that user's orders.
     */
    public int cancelAllOrders(Long userId, String symbol, Side side, OrderType type,
                               OpenOrderScope scope) {
        CancelledAll cancelled = transaction.execute(status ->
                cancelAllInTransaction(userId, symbol, side, type, scope));
        cancelled.outgoing().forEach(this::send);
        return cancelled.count();
    }

    private CancelledAll cancelAllInTransaction(Long userId, String symbol, Side side, OrderType type,
                                                OpenOrderScope scope) {
        Long symbolId = null;
        if (symbol != null && !symbol.isBlank()) {
            symbolId = spotSymbols.findBySymbol(symbol).map(SpotSymbol::getId).orElse(null);
            if (symbolId == null) {
                return new CancelledAll(0, List.of());
            }
        }
        Long onlySymbol = symbolId;
        List<SpotOrder> matching = spotOrders.findByUserIdAndStatusIn(userId, CANCELABLE_STATUSES)
                .stream()
                .filter(o -> onlySymbol == null || o.getSymbolId().equals(onlySymbol))
                .filter(o -> side == null || o.getSide() == side)
                .filter(o -> type == null || o.getOrderType() == type)
                .filter(o -> scope == OpenOrderScope.ALL
                        || (scope == OpenOrderScope.TRIGGERS) == (o.getStatus() == OrderStatus.PENDING_TRIGGER))
                .toList();
        if (matching.isEmpty()) {
            return new CancelledAll(0, List.of());
        }
        Set<Long> symbolIds = matching.stream().map(SpotOrder::getSymbolId).collect(Collectors.toSet());
        Map<Long, SpotSymbol> symbols = spotSymbols.findAllById(symbolIds).stream()
                .collect(Collectors.toMap(SpotSymbol::getId, Function.identity()));

        // Queued behind anything of the user's still on its way, as a single cancel is.
        boolean queue = outbox.hasPendingForUser(userId);
        List<Outgoing> outgoing = new ArrayList<>();
        for (SpotSymbol market : symbols.values()) {
            com.exchange.contracts.spot.v1.Command command =
                    cancelAllCommand(market.getSymbol(), userId, side, type, scope);
            outgoing.add(queue ? Outgoing.queued(outbox.enqueue(market, null, userId, command))
                    : Outgoing.direct(market, command));
        }
        return new CancelledAll(matching.size(), outgoing);
    }

    private static com.exchange.contracts.spot.v1.Command cancelAllCommand(
            String symbolCode, Long userId, Side side, OrderType type, OpenOrderScope scope) {
        com.exchange.contracts.spot.v1.CancelAllOrders.Builder cancelAll =
                com.exchange.contracts.spot.v1.CancelAllOrders.newBuilder().setUserId(userId);
        // Left unset, each field is UNSPECIFIED, which the engine reads as "any".
        if (side != null) {
            cancelAll.setSide(toProtoSide(side));
        }
        if (type != null) {
            cancelAll.setOrderType(toProtoOrderType(type));
        }
        if (scope != OpenOrderScope.ALL) {
            cancelAll.setScope(scope == OpenOrderScope.BOOK
                    ? CancelScope.CANCEL_SCOPE_BOOK
                    : CancelScope.CANCEL_SCOPE_TRIGGERS);
        }
        return com.exchange.contracts.spot.v1.Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                // The market's partition, so it lands behind every order it must see.
                .setSymbol(symbolCode)
                .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                .setCancelAllOrders(cancelAll)
                .build();
    }

    /** For a command that wrote nothing: there is no row to fall back on, so a failure is the
     *  caller's to hear about. Nothing to roll back either -- the order simply stays as it was. */
    private void publishDirect(SpotSymbol market, com.exchange.contracts.spot.v1.Command command) {
        try {
            kafka.send(Topics.SPOT_COMMANDS, market.getCommandPartition(), market.getSymbol(),
                            command.toByteArray())
                    .get(KAFKA_PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to " + market.getSymbol(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("failed to publish to " + market.getSymbol(), e);
        }
    }

    private static com.exchange.contracts.spot.v1.Command cancelCommand(UUID orderId, String symbolCode,
                                                                        Long userId, String clientOrderId) {
        com.exchange.contracts.spot.v1.CancelOrder.Builder cancelOrder =
                com.exchange.contracts.spot.v1.CancelOrder.newBuilder()
                        .setOrderId(ByteString.copyFrom(Ulid.toBytes(orderId)))
                        .setUserId(userId);
        // Carried only so the engine can echo it back on the event -- the engine matches
        // on order_id, which this service has already resolved. See CancelOrder in
        // commands.proto.
        if (clientOrderId != null) {
            cancelOrder.setClientOrderId(clientOrderId);
        }
        return com.exchange.contracts.spot.v1.Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                // Sent to the market's configured partition, as its PlaceOrder was, which
                // is what guarantees the engine sees the place before the cancel.
                .setSymbol(symbolCode)
                .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                .setCancelOrder(cancelOrder)
                .build();
    }

    private long parseMoney(String decimal, Supplier<RuntimeException> onInvalid) {
        try {
            return Money.toWire(decimal);
        } catch (IllegalArgumentException e) {
            throw onInvalid.get();
        }
    }

    private void checkOrderLimits(Long userId, SpotSymbol symbol, boolean isConditional) {
        Instant now = Instant.now();

        var symbolOverride = symbolUserLimitOverrides
                .findByUserIdAndSymbolId(userId, symbol.getId())
                .filter(o -> o.isEffective(now));
        int symbolCap = symbolOverride.map(SpotSymbolUserOrderLimitOverride::getMaxOpenOrders)
                .filter(Objects::nonNull)
                .orElse(symbol.getMaxOpenOrders());
        long symbolOpen = spotOrders.countByUserIdAndSymbolIdAndStatusIn(
                userId, symbol.getId(), OPEN_STATUSES);
        if (symbolOpen >= symbolCap) {
            throw new OrderLimitExceededException();
        }

        SpotExchangeOrderLimits defaults = exchangeLimits
                .findById(SpotExchangeOrderLimits.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "spot_exchange_order_limits singleton row is missing"));
        var accountOverride = userLimitOverrides.findByUserId(userId).filter(o -> o.isEffective(now));
        int accountCap = accountOverride.map(SpotUserOrderLimitOverride::getMaxNumOrders)
                .filter(Objects::nonNull)
                .orElse(defaults.getMaxNumOrders());
        long accountOpen = spotOrders.countByUserIdAndStatusIn(userId, OPEN_STATUSES);
        if (accountOpen >= accountCap) {
            throw new OrderLimitExceededException();
        }

        if (!isConditional) {
            return;
        }

        int symbolAlgoCap = symbolOverride
                .map(SpotSymbolUserOrderLimitOverride::getMaxOpenConditionalOrders)
                .filter(Objects::nonNull)
                .orElse(symbol.getMaxOpenConditionalOrders());
        long symbolAlgoOpen = spotOrders.countByUserIdAndSymbolIdAndOrderTypeInAndStatusIn(
                userId, symbol.getId(), CONDITIONAL_TYPES, OPEN_STATUSES);
        if (symbolAlgoOpen >= symbolAlgoCap) {
            throw new AlgoOrderLimitExceededException();
        }

        int accountAlgoCap = accountOverride.map(SpotUserOrderLimitOverride::getMaxNumAlgoOrders)
                .filter(Objects::nonNull)
                .orElse(defaults.getMaxNumAlgoOrders());
        long accountAlgoOpen = spotOrders.countByUserIdAndOrderTypeInAndStatusIn(
                userId, CONDITIONAL_TYPES, OPEN_STATUSES);
        if (accountAlgoOpen >= accountAlgoCap) {
            throw new AlgoOrderLimitExceededException();
        }
    }

    /**
     * Delegated rather than resolved here, deliberately: the same rates are shown on the
     * order form before anyone commits, and two implementations of "override beats tier"
     * would eventually disagree -- displaying one rate while charging another, with nothing
     * to fail. See {@code FeeQueryService}.
     */
    private FeeRates resolveFees(Long userId, Long masterId) {
        return feeQuery.ratesFor(userId, masterId, FeeProduct.SPOT);
    }

    private static com.exchange.contracts.spot.v1.Command placeCommand(
            UUID orderId, String symbolCode, Long userId, Side side,
            OrderType orderType, TimeInForce timeInForce, Long price, Long stopPrice,
            long quantity, Long quoteOrderQty, FeeRates feeRates,
            Integer trailingDeltaBps, String clientOrderId,
            com.exchange.contracts.spot.v1.SelfTradePreventionMode selfTradePreventionMode) {
        com.exchange.contracts.spot.v1.PlaceOrder.Builder placeOrder =
                com.exchange.contracts.spot.v1.PlaceOrder.newBuilder()
                        .setOrderId(ByteString.copyFrom(Ulid.toBytes(orderId)))
                        .setUserId(userId)
                        .setSide(toProtoSide(side))
                        .setOrderType(toProtoOrderType(orderType))
                        .setQuantity(quantity)
                        .setMakerFeeBps(feeRates.makerBps())
                        .setTakerFeeBps(feeRates.takerBps())
                        // EXPIRE_MAKER when unset (the usual spot default). Sent explicitly so
                        // the command never relies on the engine's own fallback.
                        .setSelfTradePreventionMode(selfTradePreventionMode == null
                                || selfTradePreventionMode == com.exchange.contracts.spot.v1
                                        .SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED
                                ? com.exchange.contracts.spot.v1.SelfTradePreventionMode
                                        .SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER
                                : selfTradePreventionMode);
        if (orderType != OrderType.MARKET) {
            placeOrder.setTimeInForce(toProtoTimeInForce(timeInForce));
        }
        if (price != null) {
            placeOrder.setPrice(price);
        }
        if (stopPrice != null) {
            placeOrder.setStopPrice(stopPrice);
        }
        if (quoteOrderQty != null) {
            placeOrder.setQuoteOrderQty(quoteOrderQty);
        }
        if (clientOrderId != null) {
            placeOrder.setClientOrderId(clientOrderId);
        }
        if (trailingDeltaBps != null) {
            placeOrder.setTrailingDeltaBps(trailingDeltaBps);
        }

        return com.exchange.contracts.spot.v1.Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setSymbol(symbolCode)
                .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                .setPlaceOrder(placeOrder)
                .build();
    }

    private static com.exchange.contracts.spot.v1.Side toProtoSide(Side side) {
        return side == Side.BUY
                ? com.exchange.contracts.spot.v1.Side.SIDE_BUY
                : com.exchange.contracts.spot.v1.Side.SIDE_SELL;
    }

    private static com.exchange.contracts.spot.v1.OrderType toProtoOrderType(OrderType type) {
        return switch (type) {
            case LIMIT -> com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT;
            case MARKET -> com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_MARKET;
            case STOP_LOSS -> com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS;
            case STOP_LOSS_LIMIT ->
                    com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS_LIMIT;
            case TAKE_PROFIT -> com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_TAKE_PROFIT;
            case TAKE_PROFIT_LIMIT ->
                    com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_TAKE_PROFIT_LIMIT;
            case LIMIT_MAKER -> com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT_MAKER;
        };
    }

    private static com.exchange.contracts.spot.v1.TimeInForce toProtoTimeInForce(TimeInForce tif) {
        return switch (tif) {
            case GTC -> com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC;
            case IOC -> com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_IOC;
            case FOK -> com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_FOK;
            case GTD -> throw new IllegalStateException("GTD is rejected before this is reached");
        };
    }
}
