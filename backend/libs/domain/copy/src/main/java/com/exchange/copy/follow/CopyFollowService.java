package com.exchange.copy.follow;

import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
import com.exchange.copy.follow.exception.AlreadyFollowingException;
import com.exchange.copy.follow.exception.CopyAmountTooSmallException;
import com.exchange.copy.follow.exception.CopyBalanceBelowMinimumException;
import com.exchange.copy.follow.exception.CopyLimitReachedException;
import com.exchange.copy.follow.exception.FollowNotActiveException;
import com.exchange.copy.follow.exception.FollowNotFoundException;
import com.exchange.copy.leader.exception.LeaderNotAcceptingException;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.follow.exception.SelfFollowException;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's side of copying: follow a leader, fund the copy account, stop
 * (docs/reference/copy-trading.md).
 *
 * <p><b>Following is one transaction</b>: the copy account, the follow and its funding exist
 * together or not at all, so there is never an account with no money or money with no follow.
 * The follow's id is the client's, and the funding transfer carries the same id: a request sent
 * twice finds the follow and moves nothing again.
 *
 * <p>The trading gate is the caller's, at its edge, as for an order: following and adding funds
 * are trading; taking funds out and stopping are not, and are never gated
 * (docs/reference/trading-gate.md).
 */
@Service
public class CopyFollowService {

    private final CopyFollowRepository follows;
    private final CopyLeaderRepository leaders;
    private final CopySettingsRepository settings;
    private final SubAccountService subAccounts;
    private final TransferService transfers;
    private final BalanceRepository balances;
    private final CopyCloseService closes;
    private final CopyProfitShareService shares;
    private final CopyQuoteAsset quoteAsset;

    public CopyFollowService(CopyFollowRepository follows, CopyLeaderRepository leaders,
                             CopySettingsRepository settings, SubAccountService subAccounts,
                             TransferService transfers, BalanceRepository balances, CopyCloseService closes,
                             CopyProfitShareService shares, CopyQuoteAsset quoteAsset) {
        this.shares = shares;
        this.follows = follows;
        this.leaders = leaders;
        this.settings = settings;
        this.subAccounts = subAccounts;
        this.transfers = transfers;
        this.balances = balances;
        this.closes = closes;
        this.quoteAsset = quoteAsset;
    }

    /**
     * @param followId       the client's id for this follow, so a retry is the same follow
     * @param amountPerOrder {@link CopyMode#FIXED_AMOUNT} only; ignored otherwise
     * @param slippageCapBps how far above the leader's price this follower's buys may fill, within
     *                       {@link CopyFollow#MIN_SLIPPAGE_CAP_BPS} and {@link CopyFollow#MAX_SLIPPAGE_CAP_BPS}
     * @param amount         what the copy account starts with, in the quote asset
     * @throws LeaderNotFoundException no such leader
     * @throws SelfFollowException the leader is the customer
     * @throws LeaderNotAcceptingException the leader is not active, or is full
     * @throws AlreadyFollowingException an open copy account for this leader exists
     * @throws CopyLimitReachedException too many open copy accounts
     * @throws CopyAmountTooSmallException under the mode's minimum, or an amount per order it cannot cover
     * @throws com.exchange.wallet.balance.exception.InsufficientBalanceException the customer has less than {@code amount}
     */
    @Transactional
    public CopyFollow follow(UUID followId, Long userId, UUID leaderId, CopyMode mode, Long amountPerOrder,
                             int slippageCapBps, long amount, String ipAddress) {
        if (slippageCapBps < CopyFollow.MIN_SLIPPAGE_CAP_BPS || slippageCapBps > CopyFollow.MAX_SLIPPAGE_CAP_BPS) {
            throw new IllegalArgumentException("slippage cap " + slippageCapBps + " is outside "
                    + CopyFollow.MIN_SLIPPAGE_CAP_BPS + ".." + CopyFollow.MAX_SLIPPAGE_CAP_BPS);
        }
        Optional<CopyFollow> sentBefore = follows.findById(followId);
        if (sentBefore.isPresent()) {
            return owned(userId, followId);
        }
        CopyLeader leader = leaders.findById(leaderId)
                .orElseThrow(() -> new LeaderNotFoundException("no leader " + leaderId));
        if (leader.getUserId().equals(userId)) {
            throw new SelfFollowException("a leader cannot copy their own lead account");
        }
        CopySettings set = settings();
        if (leader.getStatus() != LeaderStatus.ACTIVE
                || follows.countByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE) >= set.getMaxFollowersPerLeader()) {
            throw new LeaderNotAcceptingException("this leader is not taking followers");
        }
        List<CopyFollow> open = follows.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(f -> f.getStatus() != FollowStatus.CLOSED).toList();
        if (open.stream().anyMatch(f -> f.getLeaderId().equals(leaderId))) {
            throw new AlreadyFollowingException("there is already a copy account for this leader");
        }
        if (open.size() >= set.getMaxCopiesPerUser()) {
            throw new CopyLimitReachedException("at most " + set.getMaxCopiesPerUser() + " copy accounts");
        }
        Long perOrder = mode == CopyMode.FIXED_AMOUNT ? amountPerOrder : null;
        if (amount < minimum(set, mode) || (mode == CopyMode.FIXED_AMOUNT
                && (perOrder == null || perOrder < set.getMinFixedAmount() || perOrder > amount))) {
            throw new CopyAmountTooSmallException("the amount is under what this mode needs");
        }

        User account = subAccounts.createForCopyTrading(userId, SubPurpose.COPY, leader.getDisplayName(), ipAddress);
        CopyFollow made = new CopyFollow(followId, leaderId, userId, account.getId(), mode, perOrder,
                leader.profitShareBps(set));
        made.setSlippageCapBps(slippageCapBps);
        CopyFollow follow = follows.save(made);
        transfers.transfer(followId, userId, account.getId(), quoteAsset.id(), amount, userId);
        return follow;
    }

    /**
     * @param transferId the client's id, so a retry is the same transfer
     * @throws FollowNotFoundException not the customer's
     * @throws FollowNotActiveException closing or closed
     */
    @Transactional
    public void addFunds(Long userId, UUID followId, UUID transferId, long amount) {
        CopyFollow follow = active(userId, followId);
        transfers.transfer(transferId, userId, follow.getCopyUserId(), quoteAsset.id(), amount, userId);
    }

    /**
     * Back to the customer's own account, down to what the mode needs and no further: an
     * account with less cannot copy, and emptying one is what stopping is for.
     *
     * @throws CopyBalanceBelowMinimumException it would leave less than the mode's minimum
     */
    @Transactional
    public void removeFunds(Long userId, UUID followId, UUID transferId, long amount) {
        CopyFollow follow = active(userId, followId);
        // The leader's share of what has been realized leaves first; the follower takes out of the rest.
        shares.settle(followId, ShareTrigger.WITHDRAWAL, null);
        long available = balances.findByUserIdAndAssetId(follow.getCopyUserId(), quoteAsset.id())
                .map(Balance::getAvailable).orElse(0L);
        if (available - amount < minimum(settings(), follow.getMode())) {
            throw new CopyBalanceBelowMinimumException("that would leave less than a copy account must hold");
        }
        transfers.transfer(transferId, follow.getCopyUserId(), userId, quoteAsset.id(), amount, userId);
    }

    /**
     * Stops copying and sells what the account holds; the funds come back once that has settled
     * ({@link CopyCloseService}).
     *
     * @throws FollowNotActiveException already closing or closed
     */
    public void stop(Long userId, UUID followId) {
        owned(userId, followId);
        if (!closes.begin(followId, CloseReason.STOPPED)) {
            throw new FollowNotActiveException("this copy account is already closing");
        }
    }

    /** Newest first, closed ones included: a closed account is still the customer's history. */
    @Transactional(readOnly = true)
    public List<CopyFollow> mine(Long userId) {
        return follows.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** @throws FollowNotFoundException no such follow, or somebody else's */
    @Transactional(readOnly = true)
    public CopyFollow owned(Long userId, UUID followId) {
        return follows.findById(followId).filter(f -> f.getUserId().equals(userId))
                .orElseThrow(() -> new FollowNotFoundException("no copy account " + followId));
    }

    private CopyFollow active(Long userId, UUID followId) {
        CopyFollow follow = owned(userId, followId);
        if (follow.getStatus() != FollowStatus.ACTIVE) {
            throw new FollowNotActiveException("this copy account is " + follow.getStatus().name().toLowerCase());
        }
        return follow;
    }

    private static long minimum(CopySettings set, CopyMode mode) {
        return mode == CopyMode.FIXED_RATIO ? set.getMinFixedRatioAmount() : set.getMinFixedAmount();
    }

    private CopySettings settings() {
        return settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
    }
}
