package com.exchange.marketdata.ingest;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.BookSide;
import java.util.List;

/** Where {@link DepthWriter} reports each level's new size once the book holding it is
 *  written. An interface so the writer's tests need no broker. */
public interface LevelChangeSink {

    /** One level after one event. {@code quantity} is what the level holds now; 0 is gone. */
    record LevelChange(Market market, String symbol, long sequence, BookSide side, long price, long quantity) {
    }

    void publish(List<LevelChange> changes);
}
