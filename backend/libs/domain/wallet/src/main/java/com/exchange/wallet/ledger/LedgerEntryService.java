package com.exchange.wallet.ledger;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Read-only. Every write to the ledger happens inside {@code BalanceService}, paired
 * with the {@code Balance} mutation it explains -- see that class for why the two are
 * never written independently.
 */
@Service
public class LedgerEntryService {

    private final LedgerEntryRepository ledgerEntries;

    public LedgerEntryService(LedgerEntryRepository ledgerEntries) {
        this.ledgerEntries = ledgerEntries;
    }

    /** The account statement. */
    public Page<LedgerEntry> statement(Long userId, Long assetId, Pageable pageable) {
        return ledgerEntries.findByUserIdAndAssetIdOrderByCreatedAtDesc(userId, assetId, pageable);
    }

    /** "What did this order/trade do to balances?" -- support and dispute lookups. */
    public List<LedgerEntry> findByReference(ReferenceType referenceType, String referenceId) {
        return ledgerEntries.findByReferenceTypeAndReferenceId(referenceType, referenceId);
    }
}
