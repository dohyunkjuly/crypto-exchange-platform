package com.exchange.marketdata.tape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The reversal and the slice, with the repository mocked.
 *
 * <p>Both are easy to get subtly wrong and neither fails loudly: a tape in the wrong order
 * still renders, it just shows the oldest trade at the top and never appears to update.
 */
@ExtendWith(MockitoExtension.class)
class TradeTapeServiceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long ONE = 100_000_000L;

    @Mock
    private TradeTapeRepository tapes;

    private TradeTapeService service() {
        return new TradeTapeService(tapes);
    }

    /** Stored oldest first, read newest first. Returning them as stored would put the
     *  oldest print at the top of the panel, where the newest belongs. */
    @Test
    void returnsTheNewestTradeFirst() {
        stored(trade(1, 100), trade(2, 101), trade(3, 102));

        assertThat(service().recent(Market.SPOT, SYMBOL, 10))
                .extracting(PublicTrade::sequence)
                .containsExactly(3L, 2L, 1L);
    }

    /** The limit takes the NEWEST n, not the first n encountered -- taking from the wrong
     *  end returns the oldest trades on the tape, which is the opposite of a tape. */
    @Test
    void aLimitTakesTheNewestTradesNotTheOldest() {
        stored(trade(1, 100), trade(2, 101), trade(3, 102), trade(4, 103));

        assertThat(service().recent(Market.SPOT, SYMBOL, 2))
                .extracting(PublicTrade::sequence)
                .containsExactly(4L, 3L);
    }

    @Test
    void askingForMoreThanTheTapeHoldsReturnsWhatThereIs() {
        stored(trade(1, 100), trade(2, 101));

        assertThat(service().recent(Market.SPOT, SYMBOL, 500)).hasSize(2);
    }

    /** A market that has never traded is valid and simply has no prints. */
    @Test
    void aSymbolWithNoTapeIsEmptyRatherThanAnError() {
        when(tapes.find(Market.SPOT, "NEWUSDT")).thenReturn(Optional.empty());

        assertThat(service().recent(Market.SPOT, "NEWUSDT", 50)).isEmpty();
    }

    @Test
    void aNonPositiveLimitIsTheCallersMistake() {
        assertThatThrownBy(() -> service().recent(Market.SPOT, SYMBOL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().recent(Market.SPOT, SYMBOL, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private void stored(PublicTrade... oldestFirst) {
        TradeTape tape = new TradeTape();
        ReflectionTestUtils.setField(tape, "id", TradeTape.idOf(Market.SPOT, SYMBOL));
        ReflectionTestUtils.setField(tape, "market", Market.SPOT);
        ReflectionTestUtils.setField(tape, "symbol", SYMBOL);
        ReflectionTestUtils.setField(tape, "trades", new ArrayList<>(List.of(oldestFirst)));
        when(tapes.find(Market.SPOT, SYMBOL)).thenReturn(Optional.of(tape));
    }

    private static PublicTrade trade(long sequence, long wholePrice) {
        return new PublicTrade(sequence, wholePrice * ONE, ONE, wholePrice * ONE,
                "BUY", Instant.EPOCH.plusSeconds(sequence));
    }
}
