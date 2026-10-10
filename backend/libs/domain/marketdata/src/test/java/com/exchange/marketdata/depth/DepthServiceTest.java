package com.exchange.marketdata.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Market;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The repository is mocked: what is under test is which of the two storage paths the
 * service takes, and the clamping around it. Whether Mongo projects the right fields is
 * {@link DepthRepository}'s problem.
 *
 * <p>Every path test asserts the OTHER repository method was never called. Without that
 * the assertions have no teeth -- a service that read the full ladder every time would
 * return identical levels and pass, while quietly costing hundreds of kilobytes per read.
 */
@ExtendWith(MockitoExtension.class)
class DepthServiceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long ONE = 100_000_000L;

    @Mock
    private DepthRepository books;

    private DepthService service() {
        return new DepthService(books);
    }

    @Test
    void aShallowRequestReadsOnlyThePrecomputedTop() {
        when(books.findTop(Market.SPOT, SYMBOL)).thenReturn(Optional.of(
                DepthBookFixture.top(Market.SPOT, SYMBOL, 9,
                        List.of(new DepthLevel(100 * ONE, ONE)), List.of())));

        Depth depth = service().of(Market.SPOT, SYMBOL, 20).orElseThrow();

        assertThat(depth.bids()).containsExactly(new DepthLevel(100 * ONE, ONE));
        assertThat(depth.lastSequence()).isEqualTo(9);
        verify(books, never()).findFull(any(), anyString());
    }

    /** The boundary itself, stated as a test so the two constants cannot drift apart: at
     *  exactly TOP_LEVELS the cheap path must still be enough. */
    @Test
    void requestingExactlyTopLevelsStillTakesTheCheapPath() {
        when(books.findTop(Market.SPOT, SYMBOL)).thenReturn(Optional.of(
                DepthBookFixture.top(Market.SPOT, SYMBOL, 1, List.of(), List.of())));

        service().of(Market.SPOT, SYMBOL, DepthBook.TOP_LEVELS);

        verify(books, never()).findFull(any(), anyString());
    }

    @Test
    void reachingPastThePrecomputedTopFallsBackToTheFullLadder() {
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.of(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 11,
                        DepthBookFixture.levels(String.valueOf(100 * ONE), ONE), Map.of())));

        Depth depth = service()
                .of(Market.SPOT, SYMBOL, DepthBook.TOP_LEVELS + 1).orElseThrow();

        assertThat(depth.bids()).containsExactly(new DepthLevel(100 * ONE, ONE));
        verify(books, never()).findTop(any(), anyString());
    }

    /** Clamping happens before the path decision, so an absurd limit must not become an
     *  absurd response -- and must still land on the full-ladder path. */
    @Test
    void anOversizedLimitIsClampedRatherThanRefused() {
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.of(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 1,
                        DepthBookFixture.levels(String.valueOf(100 * ONE), ONE), Map.of())));

        Depth depth = service().of(Market.SPOT, SYMBOL, Integer.MAX_VALUE).orElseThrow();

        assertThat(depth.bids()).hasSize(1);
        verify(books).findFull(Market.SPOT, SYMBOL);
    }

    @Test
    void aNonPositiveLimitIsTheCallersMistake() {
        assertThatThrownBy(() -> service().of(Market.SPOT, SYMBOL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().of(Market.SPOT, SYMBOL, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A symbol with no book is empty, not an error: a newly listed market is perfectly
     *  valid and simply has nothing resting on it yet. */
    @Test
    void aSymbolWithNoBookYetIsEmpty() {
        when(books.findTop(Market.SPOT, "NEWUSDT")).thenReturn(Optional.empty());

        assertThat(service().of(Market.SPOT, "NEWUSDT", 20)).isEmpty();
    }
}
