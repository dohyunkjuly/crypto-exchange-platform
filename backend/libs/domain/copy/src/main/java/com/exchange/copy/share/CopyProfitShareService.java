package com.exchange.copy.share;

import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.position.CopyPosition;
import com.exchange.copy.position.CopyPositionRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The profit share (docs/reference/copy-trading.md): the leader's part of a follower's realized
 * profit, paid in the copy asset from the copy account to the leader's own account.
 *
 * <p>Cumulative, so a leader is paid once for each unit of profit:
 * {@code due = max(realized x rate - already paid, 0)}. A loss after a payout is not clawed
 * back; it has to be made up before anything more is due. The rate is the follow's own, frozen
 * when the follower started, so the formula means the same thing every time it runs.
 *
 * <p>Realized profit is read from {@code spot_trades} since the last settlement, through each
 * market's position ({@link PositionMath}); nothing on the fill path writes it. When the copy
 * account holds less of the copy asset than is due -- a later copied buy spent the proceeds --
 * what is there is paid and the rest stays owed, found again by the same formula next time.
 * Nothing is sold to pay a share: that would be a trade the follower did not choose.
 */
@Service
public class CopyProfitShareService {

    private final CopyFollowRepository follows;
    private final CopyLeaderRepository leaders;
    private final CopyOrderRepository copyOrders;
    private final CopyPositionRepository positions;
    private final CopyProfitShareRepository shares;
    private final SpotSymbolRepository markets;
    private final SpotTradeRepository trades;
    private final BalanceRepository balances;
    private final BalanceService balanceService;
    private final CopyQuoteAsset quoteAsset;

    public CopyProfitShareService(CopyFollowRepository follows, CopyLeaderRepository leaders,
                                  CopyOrderRepository copyOrders, CopyPositionRepository positions,
                                  CopyProfitShareRepository shares, SpotSymbolRepository markets,
                                  SpotTradeRepository trades, BalanceRepository balances,
                                  BalanceService balanceService, CopyQuoteAsset quoteAsset) {
        this.follows = follows;
        this.leaders = leaders;
        this.copyOrders = copyOrders;
        this.positions = positions;
        this.shares = shares;
        this.markets = markets;
        this.trades = trades;
        this.balances = balances;
        this.balanceService = balanceService;
        this.quoteAsset = quoteAsset;
    }

    /**
     * Settles one copy account now. Joins the caller's transaction, so closing and withdrawing
     * settle and move the funds together or not at all.
     *
     * @param week the Monday a {@link ShareTrigger#WEEKLY} settlement is for, and its idempotency
     *             key: a week already settled is not settled again. Null for the other triggers.
     * @return the settlement, or empty when nothing was due or the week was already done
     */
    @Transactional
    public Optional<CopyProfitShare> settle(UUID followId, ShareTrigger trigger, LocalDate week) {
        if (trigger == ShareTrigger.WEEKLY && shares.existsByFollowIdAndWeek(followId, week)) {
            return Optional.empty();
        }
        CopyFollow follow = follows.findById(followId).orElseThrow();
        follow.setRealizedProfit(follow.getRealizedProfit() + realizeSinceLast(follow));
        long due = Math.max(Money.applyBps(follow.getRealizedProfit(), follow.getProfitShareBps())
                - follow.getSharedProfit(), 0);
        if (due == 0) {
            follows.save(follow);
            return Optional.empty();
        }
        long available = balances.findByUserIdAndAssetId(follow.getCopyUserId(), quoteAsset.id())
                .map(Balance::getAvailable).orElse(0L);
        long paid = Math.min(due, available);
        CopyProfitShare share = new CopyProfitShare(Ulid.randomUuid(), followId, trigger, week,
                follow.getProfitShareBps(), follow.getRealizedProfit(), due, paid);
        if (paid > 0) {
            Long leaderAccount = leaders.findById(follow.getLeaderId()).orElseThrow().getUserId();
            balanceService.payProfitShare(follow.getCopyUserId(), quoteAsset.id(), paid, share.getId().toString(),
                    "copy-share:" + share.getId() + ":out");
            balanceService.receiveProfitShare(leaderAccount, quoteAsset.id(), paid, share.getId().toString(),
                    "copy-share:" + share.getId() + ":in");
            follow.setSharedProfit(follow.getSharedProfit() + paid);
        }
        follows.save(follow);
        return Optional.of(shares.save(share));
    }

    /** What the copy account's fills since the last settlement realized, market by market. */
    private long realizeSinceLast(CopyFollow follow) {
        long realized = 0;
        for (Long symbolId : copyOrders.marketsOf(follow.getId())) {
            SpotSymbol market = markets.findById(symbolId).orElseThrow();
            CopyPosition position = positions.findByFollowIdAndAssetId(follow.getId(), market.getBaseAssetId())
                    .orElseGet(() -> new CopyPosition(follow.getId(), market.getBaseAssetId()));
            for (SpotTrade trade : trades.ofAccountAfter(follow.getCopyUserId(), symbolId, position.getLastSequence())) {
                realized += PositionMath.apply(position, trade, follow.getCopyUserId(), market.getBaseAssetId(),
                        market.getQuoteAssetId());
            }
            positions.save(position);
        }
        return realized;
    }
}
