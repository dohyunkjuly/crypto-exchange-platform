package com.exchange.spot.copy.lead;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.spot.copy.copy.CopyService;
import com.exchange.spot.order.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A lead order is followed to its close and copied once, whatever is delivered twice on the way. */
class LeadOrderServiceTest {

    private static final long ONE = 100_000_000L;

    private final CopyLeadOrderRepository leadOrders = Mockito.mock(CopyLeadOrderRepository.class);
    private final CopyService copies = Mockito.mock(CopyService.class);
    private final LeadOrderService service = new LeadOrderService(leadOrders, copies);

    // Half the leader's USDT, for 5 at 100.
    private final CopyLeadOrder buy = new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.BUY,
            CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);

    @Test
    @DisplayName("fills add up, and a trade delivered twice is counted once")
    void fillsAddUpOnce() {
        service.traded(buy, 7, 2 * ONE, 200 * ONE);
        service.traded(buy, 7, 2 * ONE, 200 * ONE);

        assertThat(buy.getFilledQuantity()).isEqualTo(2 * ONE);
        assertThat(buy.getFilledQuoteQuantity()).isEqualTo(200 * ONE);
        Mockito.verifyNoInteractions(copies);
    }

    @Test
    @DisplayName("an order that fills in several trades is copied at the last one, with everything that filled")
    void copiedWhenWhatFilledCoversTheOrder() {
        // The engine stamps each of a taker's trades FILLED; only the amounts say which is the last.
        service.traded(buy, 7, 2 * ONE, 200 * ONE);
        service.traded(buy, 8, 2 * ONE, 200 * ONE);
        Mockito.verifyNoInteractions(copies);

        service.traded(buy, 9, ONE, 100 * ONE);

        Mockito.verify(copies).copy(buy);
        assertThat(buy.getFilledQuoteQuantity()).as("copied with the whole order, not its first trade").isEqualTo(500 * ONE);
        assertThat(buy.getCopiedAt()).isNotNull();
    }

    @Test
    @DisplayName("a buy that improved on its price has filled when its quantity has, though it spent less than it set aside")
    void aBuyThatImprovedOnItsPrice() {
        service.traded(buy, 7, 5 * ONE, 480 * ONE);

        Mockito.verify(copies).copy(buy);
    }

    @Test
    @DisplayName("an order sized in the quote asset has filled when that amount is spent")
    void quoteSized() {
        CopyLeadOrder marketBuy = new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.BUY,
                CopyLeadOrder.WHOLE / 2, 500 * ONE, 0);
        service.traded(marketBuy, 7, 3 * ONE, 300 * ONE);
        Mockito.verifyNoInteractions(copies);

        service.traded(marketBuy, 8, 2 * ONE, 200 * ONE);
        Mockito.verify(copies).copy(marketBuy);
    }

    @Test
    @DisplayName("the last trade delivered twice copies once")
    void theLastTradeTwice() {
        service.traded(buy, 7, 5 * ONE, 500 * ONE);
        service.traded(buy, 7, 5 * ONE, 500 * ONE);

        Mockito.verify(copies).copy(buy);
    }

    @Test
    @DisplayName("a copier that stopped between counting the last trade and copying copies when the trade comes again")
    void resumesAfterStoppingHalfway() {
        buy.addFill(7, 5 * ONE, 500 * ONE);   // counted, then the copier died

        service.traded(buy, 7, 5 * ONE, 500 * ONE);

        Mockito.verify(copies).copy(buy);
    }

    @Test
    @DisplayName("an order cancelled after a partial fill is copied for what filled; one that filled nothing is only closed")
    void cancelled() {
        buy.addFill(7, 2 * ONE, 200 * ONE);
        service.closed(buy);
        Mockito.verify(copies).copy(buy);

        CopyLeadOrder untouched = new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.BUY, 1, ONE, ONE);
        service.closed(untouched);
        assertThat(untouched.getCopiedAt()).isNotNull();
        Mockito.verifyNoMoreInteractions(copies);
    }

    @Test
    @DisplayName("an amend the engine took moves the fraction with the order's size, and the same answer twice moves it once")
    void amendRescales() {
        // Half the leader's USDT bought 5 at 100. Amended to 7.5 at 100: three quarters.
        OrderAmended bigger = OrderAmended.newBuilder().setNewPrice(100 * ONE).setNewQuantity(75 * ONE / 10).build();
        service.amended(buy, bigger);
        service.amended(buy, bigger);

        assertThat(buy.getFraction()).isEqualTo(CopyLeadOrder.WHOLE * 3 / 4);
        assertThat(buy.getAmount()).isEqualTo(750 * ONE);
        assertThat(buy.getQuantity()).as("and what it must fill to have closed").isEqualTo(75 * ONE / 10);

        CopyLeadOrder sell = new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.SELL,
                CopyLeadOrder.WHOLE / 5, 2 * ONE, 2 * ONE);
        service.amended(sell, OrderAmended.newBuilder().setNewPrice(100 * ONE).setNewQuantity(ONE).build());
        assertThat(sell.getFraction()).as("half the quantity, half the share of the holding")
                .isEqualTo(CopyLeadOrder.WHOLE / 10);
    }

    @Test
    @DisplayName("an amend can never make an order more than everything the leader had")
    void amendIsCapped() {
        service.amended(buy, OrderAmended.newBuilder().setNewPrice(100 * ONE).setNewQuantity(50 * ONE).build());

        assertThat(buy.getFraction()).isEqualTo(CopyLeadOrder.WHOLE);
    }
}
