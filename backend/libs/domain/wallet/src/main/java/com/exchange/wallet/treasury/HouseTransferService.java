package com.exchange.wallet.treasury;

import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff moving the exchange's own money (docs/reference/treasury.md): fees on to the treasury,
 * and the treasury out to a customer. Never money from nothing -- every move is a debit on one
 * account and the same credit on another, ledgered {@code TRANSFER} under
 * {@link ReferenceType#ADMIN} with the move's id. Who may move what is the caller's question.
 *
 * <p>The id is the caller's, so a repeated request is the same move: its debit's idempotency key
 * is already in the ledger, and nothing happens twice.
 */
@Service
public class HouseTransferService {

    private final BalanceService balances;
    private final LedgerEntryRepository ledger;

    public HouseTransferService(BalanceService balances, LedgerEntryRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
    }

    /**
     * @return false when this id has moved money before, and nothing was done now
     * @throws com.exchange.wallet.balance.exception.InsufficientBalanceException the sender's available is short
     */
    @Transactional
    public boolean move(UUID id, Long fromUserId, Long toUserId, Long assetId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("a move is a positive amount");
        }
        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("a move has two different sides");
        }
        String out = "house:" + id + ":out";
        if (ledger.existsByIdempotencyKey(out)) {
            return false;
        }
        String reference = id.toString();
        balances.transferOut(fromUserId, assetId, amount, ReferenceType.ADMIN, reference, out);
        balances.transferIn(toUserId, assetId, amount, ReferenceType.ADMIN, reference, "house:" + id + ":in");
        return true;
    }
}
