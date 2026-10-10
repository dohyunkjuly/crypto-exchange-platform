package com.exchange.copy.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.copy.position.CopyPosition;
import com.exchange.spot.trade.SpotTrade;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PositionMathTest {

    private static final long ONE = 100_000_000L;
    private static final Long ACCOUNT = 7L;
    private static final Long OTHER = 8L;
    private static final Long BASE = 2L;
    private static final Long USDT = 1L;

    private final CopyPosition position = new CopyPosition(Ulid.randomUuid(), BASE);

    @Test
    @DisplayName("a buy adds what was received, after the fee taken in the coin, at what was paid; it realizes nothing")
    void aBuy() {
        long realized = PositionMath.apply(position, buy(1, 100 * ONE, 10 * ONE, ONE / 10, BASE), ACCOUNT, BASE, USDT);

        assertThat(realized).isZero();
        assertThat(position.getQuantity()).isEqualTo(99 * ONE / 10);
        assertThat(position.getCost()).isEqualTo(1_000 * ONE);
        assertThat(position.getLastSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sell realizes the proceeds net of fee over the sold share of the average cost, and leaves the rest")
    void aSellOfPart() {
        PositionMath.apply(position, buy(1, 100 * ONE, 10 * ONE, 0, null), ACCOUNT, BASE, USDT);
        PositionMath.apply(position, buy(2, 200 * ONE, 10 * ONE, 0, null), ACCOUNT, BASE, USDT);   // 20 held at 150 average

        long realized = PositionMath.apply(position, sell(3, 180 * ONE, 5 * ONE, ONE, USDT), ACCOUNT, BASE, USDT);

        assertThat(realized).as("900 less 1 fee, over 5 x 150").isEqualTo(149 * ONE);
        assertThat(position.getQuantity()).isEqualTo(15 * ONE);
        assertThat(position.getCost()).isEqualTo(2_250 * ONE);
    }

    @Test
    @DisplayName("selling more than was counted takes the whole cost and calls the rest profit; nothing goes negative")
    void aSellOfMoreThanHeld() {
        PositionMath.apply(position, buy(1, 100 * ONE, 10 * ONE, 0, null), ACCOUNT, BASE, USDT);

        long realized = PositionMath.apply(position, sell(2, 100 * ONE, 12 * ONE, 0, null), ACCOUNT, BASE, USDT);

        assertThat(realized).isEqualTo(200 * ONE);
        assertThat(position.getQuantity()).isZero();
        assertThat(position.getCost()).isZero();
    }

    @Test
    @DisplayName("a loss is a negative realization, and the fee counts towards it")
    void aLoss() {
        PositionMath.apply(position, buy(1, 100 * ONE, 10 * ONE, 0, null), ACCOUNT, BASE, USDT);
        assertThat(PositionMath.apply(position, sell(2, 90 * ONE, 10 * ONE, ONE, USDT), ACCOUNT, BASE, USDT))
                .isEqualTo(-101 * ONE);
    }

    private static SpotTrade buy(long sequence, long price, long quantity, long fee, Long feeAsset) {
        return trade(sequence, ACCOUNT, OTHER, price, quantity, fee, feeAsset, 0, null);
    }

    private static SpotTrade sell(long sequence, long price, long quantity, long fee, Long feeAsset) {
        return trade(sequence, OTHER, ACCOUNT, price, quantity, 0, null, fee, feeAsset);
    }

    private static SpotTrade trade(long sequence, Long buyer, Long seller, long price, long quantity,
                                   long buyerFee, Long buyerFeeAsset, long sellerFee, Long sellerFeeAsset) {
        SpotTrade trade = new SpotTrade(Ulid.randomUuid(), 1L, price, quantity, Ulid.randomUuid(), Ulid.randomUuid(),
                buyer, seller, true, sequence, Instant.now());
        trade.chargeFees(buyerFee, buyerFeeAsset, sellerFee, sellerFeeAsset);
        return trade;
    }
}
