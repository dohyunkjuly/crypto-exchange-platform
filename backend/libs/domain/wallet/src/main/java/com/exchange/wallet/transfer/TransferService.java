package com.exchange.wallet.transfer;

import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money between two accounts, instantly. Who may move money to whom is not this service's
 * question -- the user domain answers that (sub-accounts) before calling. This moves it: one
 * transaction, debit then credit, both ledgered {@code TRANSFER} with the transfer's id, so a
 * failure anywhere leaves both balances as they were.
 */
@Service
public class TransferService {

    private final WalletTransferRepository transfers;
    private final BalanceService balances;

    public TransferService(WalletTransferRepository transfers, BalanceService balances) {
        this.transfers = transfers;
        this.balances = balances;
    }

    /**
     * @param id the transfer's id, chosen by the caller so a retry is the same transfer
     * @throws com.exchange.wallet.balance.exception.InsufficientBalanceException the sender's available is short
     */
    @Transactional
    public WalletTransfer transfer(UUID id, Long fromUserId, Long toUserId, Long assetId, long amount,
                                   Long requestedBy) {
        if (amount <= 0) {
            throw new IllegalArgumentException("a transfer moves a positive amount");
        }
        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("a transfer has two different sides");
        }
        WalletTransfer existing = transfers.findById(id).orElse(null);
        if (existing != null) {
            return existing;
        }
        String reference = id.toString();
        balances.transferOut(fromUserId, assetId, amount, ReferenceType.TRANSFER, reference, "transfer:" + id + ":out");
        balances.transferIn(toUserId, assetId, amount, ReferenceType.TRANSFER, reference, "transfer:" + id + ":in");
        return transfers.save(new WalletTransfer(id, fromUserId, toUserId, assetId, amount, requestedBy));
    }

    public List<WalletTransfer> history(Long master, List<Long> family, int limit) {
        return transfers.history(master, family, PageRequest.of(0, limit));
    }
}
