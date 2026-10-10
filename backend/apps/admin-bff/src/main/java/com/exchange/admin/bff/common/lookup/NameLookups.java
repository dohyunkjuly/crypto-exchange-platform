package com.exchange.admin.bff.common.lookup;

import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The names a list of rows needs, fetched once per page rather than once per row, and the
 * filters staff type resolved back to ids. Every search screen reads the same way: an email or
 * an id for a customer, a symbol for a pair or an asset.
 */
@Component
public class NameLookups {

    /** A market as a row shows it: its name and the two assets it trades. */
    public record Market(String symbol, String base, String quote) {
    }

    private final UserRepository users;
    private final SpotSymbolRepository symbols;
    private final AssetRepository assets;

    public NameLookups(UserRepository users, SpotSymbolRepository symbols, AssetRepository assets) {
        this.users = users;
        this.symbols = symbols;
        this.assets = assets;
    }

    /** An id is taken as an id; anything else as an email. Empty when it names nobody. */
    public Optional<Long> customer(String emailOrId) {
        String q = emailOrId.trim();
        if (q.matches("\\d{1,18}")) {
            return Optional.of(Long.parseLong(q));
        }
        return users.findByEmail(q.toLowerCase()).map(User::getId);
    }

    public Optional<Long> market(String symbol) {
        return symbols.findBySymbol(symbol.trim().toUpperCase()).map(SpotSymbol::getId);
    }

    public Optional<Long> asset(String symbol) {
        return assets.findBySymbol(symbol.trim().toUpperCase()).map(Asset::getId);
    }

    public Map<Long, String> emails(Collection<Long> userIds) {
        return users.findAllById(userIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
    }

    public Map<Long, Asset> assets(Collection<Long> assetIds) {
        return assets.findAllById(assetIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));
    }

    public Map<Long, Market> markets(Collection<Long> symbolIds) {
        var found = symbols.findAllById(symbolIds.stream().distinct().toList());
        Map<Long, Asset> named = assets(found.stream()
                .flatMap(m -> Stream.of(m.getBaseAssetId(), m.getQuoteAssetId())).toList());
        return found.stream().collect(Collectors.toMap(SpotSymbol::getId, m -> new Market(m.getSymbol(),
                symbolOf(named.get(m.getBaseAssetId())), symbolOf(named.get(m.getQuoteAssetId())))));
    }

    private static String symbolOf(Asset asset) {
        return asset == null ? null : asset.getSymbol();
    }
}
