package com.exchange.bff.symbol;

import com.exchange.bff.symbol.dto.SymbolView;
import com.exchange.spot.symbol.EnginePresence;
import com.exchange.spot.symbol.SymbolCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What markets exist, and the rules an order in each one must satisfy.
 *
 * <p><b>Not under {@code /bff/market}, and not market data.</b> Everything there is derived
 * from the event stream, lives in MongoDB and changes several times a second; this is
 * configuration, read from {@code spot_symbols} and {@code wallet_assets} in MySQL, and
 * changes when an operator lists or halts a market. Different source, different lifetime,
 * different caching -- a client fetches this once and keeps it, while it polls the ticker
 * list forever.
 *
 * <p>It is also what makes an order form able to refuse a bad order before sending it. Tick
 * size, quantity step and minimum notional are the difference between a form that guides
 * and one that submits and reports a rejection.
 *
 * <p>Public, like the market endpoints: which markets an exchange lists is not anybody's
 * private data, and a logged-out visitor should still see the exchange. Note that
 * {@code WebMvcConfig} must exclude this path from the authentication interceptor --
 * forgetting that does not fail loudly, it just puts a login in front of the markets list.
 */
@RestController
@RequestMapping("/bff/symbols")
public class SymbolController {

    private final SymbolCatalog catalog;
    private final EnginePresence engines;

    public SymbolController(SymbolCatalog catalog, EnginePresence engines) {
        this.catalog = catalog;
        this.engines = engines;
    }

    /**
     * Every visible market, alphabetically.
     *
     * <p>No pagination and no filtering. The whole list is a few kilobytes and a client
     * needs all of it -- the order form looks up whichever market the user switches to, so
     * fetching them one at a time would be a request per click.
     */
    @GetMapping
    public List<SymbolView> symbols() {
        return catalog.visible().stream()
                .map(m -> SymbolView.of(m, !engines.isLive(m.commandPartition())))
                .toList();
    }
}
