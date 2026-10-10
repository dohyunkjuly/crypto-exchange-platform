package com.exchange.spot.openapi.ws.depth;

import java.util.List;

/**
 * The body of a {@code depthUpdate} frame: the levels that changed since the last frame, each
 * with its <b>new absolute quantity</b> ("0" meaning gone), and the range of engine sequences
 * folded in. {@code [price, quantity]} pairs rather than named objects: a client applies these
 * in a tight loop, where the snapshot it seeds from is read as a record. Raw tick prices, no
 * grouping. The client procedure is on the developer site (api-docs/); the backend side is
 * docs/reference/depth-fold.md.
 */
public record DepthUpdateData(String symbol, long firstSequence, long lastSequence,
                              List<List<String>> bids, List<List<String>> asks) {
}
