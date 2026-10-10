package com.exchange.copy.leader;

import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.leader.exception.LeadBalanceBelowMinimumException;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A leader's money in and out of the lead account (docs/reference/copy-trading.md).
 *
 * <p><b>While the leader leads, the account keeps its minimum, and only the quote asset comes
 * out.</b> The minimum is the leader's own money at stake. And a coin taken out without being
 * sold would shrink the leader's holding with no order for anyone to copy: the next sale would
 * be a larger share of what is left, and every follower would sell too much.
 *
 * <p>Once the leader has stopped, or staff have stopped them, nobody is copying and both rules
 * fall away: everything can be taken out, in whatever it is held.
 */
@Service
public class CopyLeadAccountService {

    private final CopyLeaderRepository leaders;
    private final CopySettingsRepository settings;
    private final BalanceRepository balances;
    private final TransferService transfers;
    private final CopyQuoteAsset quoteAsset;

    public CopyLeadAccountService(CopyLeaderRepository leaders, CopySettingsRepository settings,
                                  BalanceRepository balances, TransferService transfers, CopyQuoteAsset quoteAsset) {
        this.leaders = leaders;
        this.settings = settings;
        this.balances = balances;
        this.transfers = transfers;
        this.quoteAsset = quoteAsset;
    }

    /**
     * The lead account of a customer who has one, whatever became of their leading: it is
     * theirs to trade and to empty.
     *
     * @throws NotALeaderException no lead account
     */
    @Transactional(readOnly = true)
    public Long accountId(Long userId) {
        return account(userId).getLeadUserId();
    }

    /** @throws NotALeaderException no lead account */
    @Transactional(readOnly = true)
    public List<Balance> balances(Long userId) {
        return balances.findByUserId(account(userId).getLeadUserId());
    }

    /**
     * From the leader's own account, in the quote asset: it is what a lead account buys with.
     *
     * @param transferId the client's id, so a retry is the same transfer
     */
    @Transactional
    public void fund(Long userId, UUID transferId, long amount) {
        transfers.transfer(transferId, userId, account(userId).getLeadUserId(), quoteAsset.id(), amount, userId);
    }

    /**
     * @throws LeadBalanceBelowMinimumException leading, and it is not the quote asset or would
     *         leave less of it than a lead account must keep
     */
    @Transactional
    public void withdraw(Long userId, UUID transferId, Long assetId, long amount) {
        CopyLeader leader = account(userId);
        if (leader.getStatus() == LeaderStatus.ACTIVE) {
            long available = balances.findByUserIdAndAssetId(leader.getLeadUserId(), quoteAsset.id())
                    .map(Balance::getAvailable).orElse(0L);
            CopySettings set = settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
            if (!assetId.equals(quoteAsset.id()) || available - amount < set.getLeadMinBalance()) {
                throw new LeadBalanceBelowMinimumException("a lead account keeps its minimum while it leads");
            }
        }
        transfers.transfer(transferId, leader.getLeadUserId(), userId, assetId, amount, userId);
    }

    /** The customer's leadership, if it ever got as far as a lead account. */
    private CopyLeader account(Long userId) {
        return leaders.findByUserId(userId).filter(l -> l.getLeadUserId() != null)
                .orElseThrow(() -> new NotALeaderException("this account has no lead account"));
    }
}
