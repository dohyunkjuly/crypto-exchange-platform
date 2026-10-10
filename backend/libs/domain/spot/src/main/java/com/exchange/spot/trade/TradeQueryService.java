package com.exchange.spot.trade;

import com.exchange.spot.history.TimeRange;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.dto.UserTrade;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading a user's own fills back.
 *
 * <p>Separate from the public tape in {@code libs/domain/marketdata}, and deliberately so:
 * that one answers "what has this market been doing" for anybody, this one answers "what did
 * I pay" for exactly one person. They read different stores, have different audiences and
 * carry different fields; the only thing they share is the word "trade".
 *
 * <p>Lives here rather than in web-bff because spot-open-api needs the identical query for
 * its own {@code /v1/myTrades} -- the "does more than one app need it?" test in
 * AGENTS.md. It therefore throws plain exceptions with no error code attached.
 *
 * <p><b>Every query is scoped by {@code userId}, and the projection discards the
 * counterparty before returning.</b> Both halves matter. A row reached through either side
 * still holds the other person's id and fee, so a service that returned {@code SpotTrade}
 * would leave every caller one careless mapper away from publishing it -- the same mistake
 * the type system cannot catch, because a leaked field still compiles.
 */
@Service
@Transactional(readOnly = true)
public class TradeQueryService {

    /** A page a user can actually read, and a bound on how much a single request costs. */
    public static final int MAX_PAGE_SIZE = 500;

    private final SpotTradeRepository spotTrades;
    private final SpotSymbolRepository spotSymbols;
    private final AssetRepository assets;
    private final SpotOrderRepository spotOrders;

    public TradeQueryService(SpotTradeRepository spotTrades, SpotSymbolRepository spotSymbols,
                             AssetRepository assets, SpotOrderRepository spotOrders) {
        this.spotTrades = spotTrades;
        this.spotSymbols = spotSymbols;
        this.assets = assets;
        this.spotOrders = spotOrders;
    }

    /**
     * This user's fills, newest first. Across every market when {@code symbol} is null, or
     * one market's trade-history panel when it is given.
     *
     * <p>An unknown symbol yields an empty page rather than an error: a user asking about a
     * market they have never traded and one that does not exist get the same honest answer,
     * "you have no fills here", and distinguishing them would tell an unauthenticated
     * scraper which symbols are real.
     *
     * <p>{@code side} is what this user did; null means both.
     */
    public List<UserTrade> history(Long userId, String symbol, UserTrade.Side side,
                                   TimeRange range, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), clamp(size));
        Long symbolId = null;
        if (symbol != null && !symbol.isBlank()) {
            symbolId = spotSymbols.findBySymbol(symbol).map(SpotSymbol::getId).orElse(null);
            if (symbolId == null) {
                return List.of();
            }
        }
        return project(userId, spotTrades.findUserTrades(userId, symbolId,
                side != UserTrade.Side.SELL, side != UserTrade.Side.BUY,
                range.from(), range.to(), request));
    }

    /** Names resolved in bulk -- three queries for a page, not three per row. */
    private List<UserTrade> project(Long userId, List<SpotTrade> trades) {
        if (trades.isEmpty()) {
            return List.of();
        }
        Set<Long> symbolIds = trades.stream()
                .map(SpotTrade::getSymbolId).collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> symbolNames = spotSymbols.findAllById(symbolIds).stream()
                .collect(Collectors.toMap(SpotSymbol::getId, SpotSymbol::getSymbol));

        Set<Long> feeAssetIds = new HashSet<>();
        for (SpotTrade trade : trades) {
            feeAssetIds.add(feeAssetIdFor(userId, trade));
        }
        feeAssetIds.remove(null);
        Map<Long, Asset> assetsById = assets.findAllById(feeAssetIds).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

        // Only this user's own order ids: the counterparty's order type is theirs to know.
        Set<UUID> orderIds = trades.stream().map(trade -> ownOrderId(userId, trade))
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, OrderType> orderTypes = spotOrders.findAllById(orderIds).stream()
                .collect(Collectors.toMap(SpotOrder::getId, SpotOrder::getOrderType));

        return trades.stream()
                .map(trade -> toUserTrade(userId, trade, symbolNames, assetsById, orderTypes))
                .toList();
    }

    /**
     * The side-picking, in one place.
     *
     * <p>A {@code SpotTrade} records a buyer and a seller, plus {@code buyerIsMaker}. So the
     * user's role is the buyer's flag when they bought and its opposite when they sold --
     * the step most likely to be written backwards, and one that fails by showing somebody
     * the wrong fee rather than by throwing.
     *
     * <p>A row where the same user is both sides cannot exist -- {@code SpotTrade}'s
     * constructor rejects it outright, so there is no ambiguous case to resolve here and no
     * fallback pretending to.
     */
    private static UserTrade toUserTrade(Long userId, SpotTrade trade,
                                         Map<Long, String> symbolNames,
                                         Map<Long, Asset> assetsById,
                                         Map<UUID, OrderType> orderTypes) {
        boolean userBought = userId.equals(trade.getBuyerUserId());
        Long feeAssetId = feeAssetIdFor(userId, trade);
        Asset feeAsset = feeAssetId == null ? null : assetsById.get(feeAssetId);

        return new UserTrade(trade.getId(),
                symbolNames.get(trade.getSymbolId()),
                ownOrderId(userId, trade),
                orderTypes.get(ownOrderId(userId, trade)),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getQuoteQuantity(),
                userBought ? UserTrade.Side.BUY : UserTrade.Side.SELL,
                userBought == trade.isBuyerIsMaker(),
                userBought ? trade.getBuyerFee() : trade.getSellerFee(),
                feeAsset == null ? null : feeAsset.getSymbol(),
                trade.getExecutedAt());
    }

    private static UUID ownOrderId(Long userId, SpotTrade trade) {
        return userId.equals(trade.getBuyerUserId()) ? trade.getBuyerOrderId() : trade.getSellerOrderId();
    }

    private static Long feeAssetIdFor(Long userId, SpotTrade trade) {
        return userId.equals(trade.getBuyerUserId())
                ? trade.getBuyerFeeAssetId()
                : trade.getSellerFeeAssetId();
    }

    private static int clamp(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, was " + size);
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
