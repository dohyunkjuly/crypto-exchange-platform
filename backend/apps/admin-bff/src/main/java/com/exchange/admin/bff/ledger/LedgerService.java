package com.exchange.admin.bff.ledger;

import com.exchange.admin.bff.common.lookup.NameLookups;
import com.exchange.admin.bff.common.paging.Chunk;
import com.exchange.admin.bff.ledger.dto.LedgerViews.Row;
import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.ledger.LedgerEntry;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

/**
 * Every balance movement, as the ledger recorded it: the answer to "where did my money go", and
 * to whether a movement was recorded as what it was. The exchange's own accounts are listed like
 * anyone's, by their email. Read only; the ledger is append-only everywhere.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository ledger;
    private final NameLookups lookups;

    public LedgerService(LedgerEntryRepository ledger, NameLookups lookups) {
        this.ledger = ledger;
        this.lookups = lookups;
    }

    /**
     * @param customer an email or a customer id
     * @param from inclusive; {@code to} is exclusive
     */
    public Chunk<Row> list(String customer, String asset, LedgerEntryType type, Instant from, Instant to, int page,
                           int size) {
        PageRequest request = Chunk.request(page, size);
        // Named but unknown: an empty page, never the whole ledger.
        Optional<Long> userId = blank(customer) ? Optional.empty() : lookups.customer(customer);
        Optional<Long> assetId = blank(asset) ? Optional.empty() : lookups.asset(asset);
        if ((!blank(customer) && userId.isEmpty()) || (!blank(asset) && assetId.isEmpty())) {
            return Chunk.empty(request);
        }

        Slice<LedgerEntry> found = userId.isPresent()
                ? ledger.statement(userId.get(), assetId.orElse(null), type, from, to, request)
                : ledger.latest(assetId.orElse(null), type, from == null ? null : Ulid.floor(from),
                        to == null ? null : Ulid.floor(to), request);
        return Chunk.of(rows(found.getContent()), found);
    }

    private List<Row> rows(List<LedgerEntry> page) {
        Map<Long, String> emails = lookups.emails(page.stream().map(LedgerEntry::getUserId).toList());
        Map<Long, Asset> assets = lookups.assets(page.stream().map(LedgerEntry::getAssetId).toList());
        return page.stream().map(e -> {
            Asset a = assets.get(e.getAssetId());
            return new Row(e.getId().toString(), e.getUserId(), emails.get(e.getUserId()),
                    a == null ? "#" + e.getAssetId() : a.getSymbol(), a == null ? 8 : a.getDisplayDecimals(),
                    e.getEntryType().name(), Money.toDecimal(e.getAmount()), Money.toDecimal(e.getBalanceAfter()),
                    e.getReferenceType() == null ? null : e.getReferenceType().name(), e.getReferenceId(), e.getCreatedAt());
        }).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
