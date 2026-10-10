package com.exchange.spot.openapi.ws.depth;

import com.exchange.marketdata.stream.WsChannel;
import org.springframework.stereotype.Component;

/**
 * {@code depthUpdate} -- the full-ladder feed: the levels that changed, each with its new size.
 * The one channel whose frames are not the whole state (design doc, "Depth"): a client seeds a
 * book from the REST snapshot and applies these in sequence, resyncing on a gap. No opening
 * frame -- the snapshot is REST's job, and a frame that was not a change would break the
 * continuity check.
 */
@Component
public class DepthUpdateChannel implements WsChannel {

    public static final String NAME = "depthUpdate";

    @Override
    public String name() {
        return NAME;
    }
}
