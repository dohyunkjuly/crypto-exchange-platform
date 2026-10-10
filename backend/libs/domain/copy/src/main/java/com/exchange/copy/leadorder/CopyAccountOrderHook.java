package com.exchange.copy.leadorder;

import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.common.money.Money;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SubAccountOrderHook;
import com.exchange.spot.order.exception.ManualOrderNotAllowedException;
import com.exchange.spot.order.exception.MarketNotAllowedException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the order path asks of a lead or a copy account (docs/reference/copy-trading.md).
 *
 * <ul>
 *   <li><b>A copy account takes no order placed by hand.</b> Only the copier trades it, through
 *       its own door; an order arriving here did not come from it.
 *   <li><b>A lead account trades markets quoted in the copy asset only</b>, because a follower's
 *       copy account is funded in it and nothing else.
 *   <li><b>A lead account's order leaves its fraction behind</b>, in this transaction: of the
 *       available quote for a buy, of the whole holding for a sell. Once the order closes the
 *       balance has moved and the fraction is gone. An amend is the copier's to follow, from
 *       the engine's own answer: only that says whether the amend took.
 * </ul>
 *
 * <p>One read by primary key for an ordinary sub-account's order; a master's never gets here.
 */
@Component
public class CopyAccountOrderHook implements SubAccountOrderHook {

    private final UserRepository users;
    private final CopyLeaderRepository leaders;
    private final CopyLeadOrderRepository leadOrders;
    private final BalanceRepository balances;
    private final CopyQuoteAsset quoteAsset;

    public CopyAccountOrderHook(UserRepository users, CopyLeaderRepository leaders,
                                CopyLeadOrderRepository leadOrders, BalanceRepository balances,
                                CopyQuoteAsset quoteAsset) {
        this.users = users;
        this.leaders = leaders;
        this.leadOrders = leadOrders;
        this.balances = balances;
        this.quoteAsset = quoteAsset;
    }

    @Override
    public void beforeLock(UUID orderId, Long userId, SpotSymbol market, Side side, long lockAmount, long quantity) {
        SubPurpose purpose = users.findById(userId).map(u -> u.getSubPurpose()).orElse(SubPurpose.STANDARD);
        if (purpose == SubPurpose.COPY) {
            throw new ManualOrderNotAllowedException("a copy account is traded by copying, not by hand");
        }
        if (purpose != SubPurpose.LEAD) {
            return;
        }
        if (!market.getQuoteAssetId().equals(quoteAsset.id())) {
            throw new MarketNotAllowedException("a lead account trades " + quoteAsset.symbol() + " markets only");
        }
        Optional<CopyLeader> leader = leaders.findByLeadUserId(userId)
                .filter(l -> l.getStatus() == LeaderStatus.ACTIVE);
        if (leader.isEmpty()) {
            return; // suspended or closed: its orders are its own, and nobody copies them
        }
        long basis = basis(userId, market, side);
        if (basis <= 0) {
            return; // nothing to draw on: the lock that follows refuses the order
        }
        leadOrders.save(new CopyLeadOrder(orderId, leader.get().getId(), market.getId(), side,
                fraction(lockAmount, basis), lockAmount, quantity));
    }

    /** What the order is a share of: the available quote for a buy, everything held of the base for a sell. */
    private long basis(Long userId, SpotSymbol market, Side side) {
        Long assetId = side == Side.BUY ? market.getQuoteAssetId() : market.getBaseAssetId();
        Optional<Balance> balance = balances.findByUserIdAndAssetId(userId, assetId);
        if (balance.isEmpty()) {
            return 0;
        }
        return side == Side.BUY ? balance.get().getAvailable() : balance.get().getTotal();
    }

    /** At least the smallest fraction there is, at most all of it: a rounding must not write a row the table refuses. */
    static long fraction(long amount, long basis) {
        return Math.max(1, Math.min(CopyLeadOrder.WHOLE, Money.divide(amount, basis)));
    }
}
