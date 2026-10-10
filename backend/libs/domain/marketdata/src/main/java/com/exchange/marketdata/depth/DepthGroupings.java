package com.exchange.marketdata.depth;

import java.util.List;
import java.util.Optional;

/** Which groupings a market offers its depth at (1e8-scaled), or empty for a market this
 *  process has no listing for. The listing lives in the spot module, which depends on this one,
 *  so the depth channel asks through an interface the catalog implements. */
public interface DepthGroupings {
    Optional<List<Long>> groupingsOf(String symbol);
}
