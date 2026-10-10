package com.exchange.spot.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.spot.history.TimeRange;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.dto.UserTrade;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The side-picking, which is the whole reason this class exists.
 *
 * <p>All four combinations are covered because the rule inverts on one of them: a buyer's
 * maker flag is the buyer's, so a <em>seller</em> is a maker exactly when the buyer was not.
 * Getting that backwards does not throw and does not fail to compile -- it shows a user the
 * counterparty's role and, one line later, the counterparty's fee.
 */
@ExtendWith(MockitoExtension.class)
class TradeQueryServiceTest {

    private static final long ME = 11L;
    private static final long THEM = 22L;
    private static final long ONE = 100_000_000L;
    private static final long BUYER_FEE = 7_000L;
    private static final long SELLER_FEE = 9_000L;
    private static final Long BASE_ASSET = 1L;
    private static final Long QUOTE_ASSET = 2L;
    private static final TimeRange RANGE =
            TimeRange.of(null, Instant.parse("2026-09-12T00:00:00Z"), null);

    @Mock
    private SpotTradeRepository spotTrades;

    @Mock
    private SpotSymbolRepository spotSymbols;

    @Mock
    private AssetRepository assets;

    @Mock
    private SpotOrderRepository spotOrders;

    private TradeQueryService service() {
        return new TradeQueryService(spotTrades, spotSymbols, assets, spotOrders);
    }

    @Test
    void aFillCarriesTheTypeOfTheUsersOwnOrder() {
        UUID mine = UUID.randomUUID();
        givenTrade(ME, THEM, true, mine, UUID.randomUUID());
        when(spotOrders.findAllById(any())).thenReturn(List.of(new SpotOrder(mine, ME, 5L,
                Side.BUY, OrderType.STOP_LOSS_LIMIT, TimeInForce.GTC, ONE, ONE, ONE, null,
                0, 0, null, null)));

        assertThat(only().orderType()).isEqualTo(OrderType.STOP_LOSS_LIMIT);
        // The seller's order id must never be looked up on the buyer's behalf.
        verify(spotOrders).findAllById(Set.of(mine));
    }

    /** The side filter is what this user did, so it picks the buyer or seller column. */
    @Test
    void aSideFilterReadsOnlyTheMatchingColumn() {
        service().history(ME, null, UserTrade.Side.BUY, RANGE, 0, 50);
        verify(spotTrades).findUserTrades(eq(ME), isNull(), eq(true), eq(false),
                eq(RANGE.from()), eq(RANGE.to()), any(Pageable.class));

        service().history(ME, null, UserTrade.Side.SELL, RANGE, 0, 50);
        verify(spotTrades).findUserTrades(eq(ME), isNull(), eq(false), eq(true),
                eq(RANGE.from()), eq(RANGE.to()), any(Pageable.class));
    }

    @Test
    void aBuyerWhoWasTheMakerSeesTheirOwnSideRoleAndFee() {
        givenTrade(ME, THEM, true);

        UserTrade mine = only();

        assertThat(mine.side()).isEqualTo(UserTrade.Side.BUY);
        assertThat(mine.isMaker()).isTrue();
        assertThat(mine.fee()).isEqualTo(BUYER_FEE);
        assertThat(mine.feeAsset()).isEqualTo("BTC");
    }

    @Test
    void aBuyerWhoWasTheTakerIsNotAMaker() {
        givenTrade(ME, THEM, false);

        UserTrade mine = only();

        assertThat(mine.side()).isEqualTo(UserTrade.Side.BUY);
        assertThat(mine.isMaker()).isFalse();
        assertThat(mine.fee()).isEqualTo(BUYER_FEE);
    }

    /** The inversion. {@code buyerIsMaker} is true, so the SELLER was the taker -- reading
     *  the flag straight would report this user as a maker and pay them a rebate they never
     *  earned. */
    @Test
    void aSellerIsAMakerExactlyWhenTheBuyerWasNot() {
        givenTrade(THEM, ME, true);

        UserTrade mine = only();

        assertThat(mine.side()).isEqualTo(UserTrade.Side.SELL);
        assertThat(mine.isMaker()).isFalse();
        assertThat(mine.fee()).isEqualTo(SELLER_FEE);
        assertThat(mine.feeAsset()).isEqualTo("USDT");
    }

    @Test
    void aSellerAgainstATakingBuyerIsTheMaker() {
        givenTrade(THEM, ME, false);

        UserTrade mine = only();

        assertThat(mine.side()).isEqualTo(UserTrade.Side.SELL);
        assertThat(mine.isMaker()).isTrue();
        assertThat(mine.fee()).isEqualTo(SELLER_FEE);
    }

    /** The counterparty's fee differs from this user's on purpose, so a projection that
     *  reached for the wrong one is visible rather than coincidentally equal. */
    @Test
    void theCounterpartysFeeIsNeverReturned() {
        givenTrade(THEM, ME, true);

        assertThat(only().fee()).isNotEqualTo(BUYER_FEE);
    }

    /** A user asking about a market they have never traded and one that does not exist get
     *  the same answer -- distinguishing them would say which symbols are real. */
    @Test
    void anUnknownSymbolIsAnEmptyPageRatherThanAnError() {
        when(spotSymbols.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThat(service().history(ME, "NOPE", null, RANGE, 0, 50)).isEmpty();
        verify(spotTrades, never()).findUserTrades(anyLong(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /** No symbol means every market: a null symbol id, not a failed lookup. */
    @Test
    void noSymbolReadsEveryMarket() {
        when(spotTrades.findUserTrades(eq(ME), isNull(), eq(true), eq(true), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        service().history(ME, null, null, RANGE, 0, 50);

        verify(spotTrades).findUserTrades(eq(ME), isNull(), eq(true), eq(true), any(), any(), any(Pageable.class));
    }

    @Test
    void aNonPositivePageSizeIsTheCallersMistake() {
        assertThatThrownBy(() -> service().history(ME, null, null, RANGE, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private UserTrade only() {
        List<UserTrade> found = service().history(ME, "BTCUSDT", null, RANGE, 0, 50);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private void givenTrade(long buyerUserId, long sellerUserId, boolean buyerIsMaker) {
        givenTrade(buyerUserId, sellerUserId, buyerIsMaker, UUID.randomUUID(), UUID.randomUUID());
    }

    private void givenTrade(long buyerUserId, long sellerUserId, boolean buyerIsMaker,
                            UUID buyerOrderId, UUID sellerOrderId) {
        SpotTrade trade = new SpotTrade(UUID.randomUUID(), 5L, 100 * ONE, ONE,
                buyerOrderId, sellerOrderId, buyerUserId, sellerUserId,
                buyerIsMaker, 1L, Instant.EPOCH);
        // The buyer is charged in the base asset (what they received), the seller in quote.
        trade.chargeFees(BUYER_FEE, BASE_ASSET, SELLER_FEE, QUOTE_ASSET);

        SpotSymbol symbol = new SpotSymbol("BTCUSDT", BASE_ASSET, QUOTE_ASSET,
                1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0);
        ReflectionTestUtils.setField(symbol, "id", 5L);

        when(spotSymbols.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
        when(spotTrades.findUserTrades(eq(ME), eq(5L), anyBoolean(), anyBoolean(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(trade));
        when(spotSymbols.findAllById(any())).thenReturn(List.of(symbol));
        when(assets.findAllById(any())).thenReturn(List.of(
                asset(BASE_ASSET, "BTC"), asset(QUOTE_ASSET, "USDT")));
    }

    private static Asset asset(Long id, String code) {
        Asset asset = new Asset(code, code, com.exchange.wallet.asset.AssetType.CRYPTO, 8);
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }
}
