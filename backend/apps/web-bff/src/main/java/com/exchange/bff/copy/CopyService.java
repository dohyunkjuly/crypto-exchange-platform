package com.exchange.bff.copy;

import com.exchange.bff.copy.dto.CopyViews.LeadTrade;
import com.exchange.bff.copy.dto.CopyViews.Portfolio;
import com.exchange.bff.copy.dto.CopyViews.Position;
import com.exchange.bff.copy.dto.CopyViews.SeriesPoint;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.spot.order.Side;
import java.util.Comparator;
import java.time.Instant;
import com.exchange.copy.stats.CopyLeaderSnapshot;
import com.exchange.copy.stats.CopyLeaderSnapshotRepository;
import com.exchange.copy.stats.ReturnMath;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import com.exchange.copy.stats.CopyLeaderStats;
import com.exchange.copy.stats.CopyLeaderStatsRepository;
import java.math.BigDecimal;
import com.exchange.bff.copy.dto.CopyViews.ShareLine;
import com.exchange.bff.copy.dto.CopyViews.Stats;
import com.exchange.copy.share.CopyProfitShareRepository;
import com.exchange.bff.copy.dto.CopyViews.Window;
import com.exchange.bff.apikey.dto.ApiKeyView;
import com.exchange.bff.apikey.dto.CreatedApiKeyView;
import com.exchange.bff.subaccount.dto.CreateSubAccountApiKeyRequest;
import com.exchange.user.apikey.ApiKeyRevokedReason;
import com.exchange.user.apikey.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exchange.bff.copy.dto.CopyViews.CopyLine;
import com.exchange.bff.copy.dto.CopyViews.MyLead;
import com.exchange.bff.copy.dto.CopyViews.LeadWithdrawRequest;
import com.exchange.bff.copy.dto.CopyViews.LeadState;
import com.exchange.bff.copy.dto.CopyViews.LeadPage;
import com.exchange.bff.copy.dto.CopyViews.ApplyRequest;
import com.exchange.bff.copy.dto.CopyViews.Follow;
import com.exchange.bff.copy.dto.CopyViews.FollowDetail;
import com.exchange.bff.copy.dto.CopyViews.FollowRequest;
import com.exchange.bff.copy.dto.CopyViews.FundsRequest;
import com.exchange.bff.copy.dto.CopyViews.Holding;
import com.exchange.bff.copy.dto.CopyViews.Leader;
import com.exchange.bff.copy.dto.CopyViews.LeaderProfile;
import com.exchange.bff.copy.dto.CopyViews.Leaders;
import com.exchange.bff.copy.dto.CopyViews.Terms;
import com.exchange.common.money.Money;
import com.exchange.copy.follow.CopyFollowService;
import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.leader.CopyLeadAccountService;
import com.exchange.copy.leader.CopyLeaderApplicationService;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.copy.leader.CopyLeaderService.Listed;
import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Copy trading for the web (docs/reference/copy-trading.md): the leaders to choose from, and a
 * customer's own copy accounts. The rules are {@link CopyFollowService}'s; this turns its rows
 * into what a page shows -- a leader's name for an id, an asset's symbol for an id, money as
 * decimal strings.
 */
@Service
public class CopyService {

    /** A page of any history here -- copies, settlements, a leader's trades -- as on the site's other history pages. */
    private static final int PER_PAGE = 20;

    /** How far back the card's line goes: the same month as the 30-day figures beside it. */
    private static final int LINE_DAYS = 30;
    /** The profile's trade history: one screen of the lead account's latest fills. */

    private final CopyLeaderService leaders;
    private final CopyFollowService follows;
    private final CopyLeaderRepository leaderRows;
    private final CopyOrderRepository copyOrders;
    private final CopySettingsRepository settings;
    private final CopyQuoteAsset quoteAsset;
    private final BalanceService balances;
    private final AssetService assets;
    private final SpotSymbolRepository markets;
    private final CopyLeadAccountService leadAccount;
    private final SpotTradeRepository trades;
    private final CopyLeaderApplicationService applications;
    private final CopyLeaderStatsRepository stats;
    private final CopyLeaderSnapshotRepository snapshots;
    private final CopyProfitShareRepository shares;
    private final CopyCloseService closes;
    private final ApiKeyService apiKeys;
    private final ObjectMapper json;
    private final SpotOrderRepository spotOrders;

    public CopyService(CopyLeaderService leaders, CopyFollowService follows, CopyLeaderRepository leaderRows,
                       CopyOrderRepository copyOrders, CopySettingsRepository settings, CopyQuoteAsset quoteAsset,
                       BalanceService balances, AssetService assets, SpotSymbolRepository markets,
                       CopyLeadAccountService leadAccount, ApiKeyService apiKeys, ObjectMapper json,
                       CopyLeaderStatsRepository stats, CopyLeaderSnapshotRepository snapshots,
                       CopyProfitShareRepository shares, CopyCloseService closes, SpotTradeRepository trades,
                       CopyLeaderApplicationService applications, SpotOrderRepository spotOrders) {
        this.applications = applications;
        this.spotOrders = spotOrders;
        this.shares = shares;
        this.closes = closes;
        this.trades = trades;
        this.leadAccount = leadAccount;
        this.stats = stats;
        this.snapshots = snapshots;
        this.apiKeys = apiKeys;
        this.json = json;
        this.leaders = leaders;
        this.follows = follows;
        this.leaderRows = leaderRows;
        this.copyOrders = copyOrders;
        this.settings = settings;
        this.quoteAsset = quoteAsset;
        this.balances = balances;
        this.assets = assets;
        this.markets = markets;
    }

    public Leaders leaders() {
        CopySettings set = settings();
        List<Listed> active = leaders.listActive();
        Map<UUID, CopyLeaderStats> figures = stats.findAllById(active.stream().map(l -> l.leader().getId()).toList())
                .stream().collect(Collectors.toMap(CopyLeaderStats::getLeaderId, Function.identity()));
        Map<UUID, List<SeriesPoint>> lines = lines(figures.keySet());
        return new Leaders(active.stream().map(l -> leader(l, set, figures.get(l.leader().getId()),
                lines.get(l.leader().getId()))).toList(), terms(set));
    }

    public LeaderProfile leader(UUID leaderId) {
        CopySettings set = settings();
        Listed listed = leaders.getActive(leaderId);
        Long lead = listed.leader().getLeadUserId();
        return new LeaderProfile(leader(listed, set, stats.findById(leaderId).orElse(null), lines(List.of(leaderId)).get(leaderId)),
                terms(set),
                snapshots.findFirstByLeaderIdOrderByDayDesc(leaderId).map(s -> Money.toDecimal(s.getEquity())).orElse(null),
                listed.leader().isShowPortfolio() && lead != null ? portfolio(lead) : null);
    }

    /**
     * One page of the lead account's fills, newest first. Empty while the leader keeps the
     * portfolio private, as the profile shows no portfolio then either.
     */
    public List<LeadTrade> leaderTrades(UUID leaderId, int page) {
        CopyLeader leader = leaders.getActive(leaderId).leader();
        Long lead = leader.getLeadUserId();
        if (lead == null || !leader.isShowPortfolio()) {
            return List.of();
        }
        List<SpotTrade> rows = trades.findUserTrades(lead, null, true, true, Instant.EPOCH, Instant.now(),
                PageRequest.of(Math.max(page, 0), PER_PAGE));
        Map<Long, String> symbols = markets.findAllById(rows.stream().map(SpotTrade::getSymbolId).distinct().toList())
                .stream().collect(Collectors.toMap(SpotSymbol::getId, SpotSymbol::getSymbol));
        return rows.stream().map(t -> new LeadTrade(t.getExecutedAt(), symbols.get(t.getSymbolId()),
                lead.equals(t.getBuyerUserId()) ? Side.BUY : Side.SELL, Money.toDecimal(t.getPrice()),
                Money.toDecimal(t.getQuantity()), Money.toDecimal(t.getQuoteQuantity()))).toList();
    }

    /** The lead account's holdings valued at each market's last trade. */
    private Portfolio portfolio(Long lead) {
        List<Position> positions = new ArrayList<>();
        for (Balance held : balances.list(lead)) {
            if (held.getTotal() <= 0) {
                continue;
            }
            Asset asset = assets.getById(held.getAssetId());
            Long value = held.getAssetId().equals(quoteAsset.id()) ? held.getTotal()
                    : markets.findByBaseAssetIdAndQuoteAssetId(held.getAssetId(), quoteAsset.id())
                            .flatMap(m -> trades.lastBefore(m.getId(), Instant.now()))
                            .map(t -> Money.multiply(t.getPrice(), held.getTotal())).orElse(null);
            positions.add(new Position(asset.getSymbol(), Money.toDecimal(held.getTotal()),
                    value == null ? null : Money.toDecimal(value)));
        }
        positions.sort(Comparator.comparing((Position p) -> p.value() == null ? BigDecimal.ZERO : new BigDecimal(p.value())).reversed());
        return new Portfolio(positions);
    }

    public MyLead setPortfolioShown(Long userId, boolean shown) {
        return myLead(new Listed(leaders.setPortfolioShown(userId, shown), 0));
    }

    public Follow follow(Long userId, FollowRequest request, String ipAddress) {
        CopyFollow made = follows.follow(request.followId(), userId, request.leaderId(), request.mode(),
                request.amountPerOrder() == null ? null : Money.toWire(request.amountPerOrder()),
                request.slippageCapBps(), Money.toWire(request.amount()), ipAddress);
        return views(List.of(made)).get(0);
    }

    public List<Follow> mine(Long userId) {
        return views(follows.mine(userId));
    }

    public FollowDetail detail(Long userId, UUID followId) {
        return new FollowDetail(views(List.of(follows.owned(userId, followId))).get(0));
    }

    /** One page of the account's profit-share settlements, newest first. */
    public List<ShareLine> shares(Long userId, UUID followId, int page) {
        follows.owned(userId, followId);
        return shares.findByFollowIdOrderByCreatedAtDesc(followId, PageRequest.of(Math.max(page, 0), PER_PAGE)).stream()
                .map(s -> new ShareLine(s.getId(), s.getTrigger(), Money.toDecimal(s.getDue()), Money.toDecimal(s.getPaid()),
                        s.getCreatedAt())).toList();
    }

    /** One page of the account's copies, newest first, each with what its order traded. */
    public List<CopyLine> copies(Long userId, UUID followId, int page) {
        follows.owned(userId, followId);
        List<CopyOrder> rows = copyOrders.findByFollowIdOrderByCreatedAtDesc(followId,
                PageRequest.of(Math.max(page, 0), PER_PAGE));
        Map<Long, String> symbols = markets.findAllById(rows.stream().map(CopyOrder::getSymbolId).distinct().toList())
                .stream().collect(Collectors.toMap(SpotSymbol::getId, SpotSymbol::getSymbol));
        Map<UUID, SpotOrder> placed = spotOrders.findAllById(rows.stream().map(CopyOrder::getOrderId)
                        .filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(SpotOrder::getId, o -> o));
        return rows.stream().map(c -> {
            SpotOrder order = c.getOrderId() == null ? null : placed.get(c.getOrderId());
            boolean traded = order != null && order.getFilledQuantity() > 0;
            return new CopyLine(c.getId(), symbols.get(c.getSymbolId()), c.getSide(), c.getStatus(), c.getReason(),
                    c.getOrderId(), c.getLeadOrderId() == null, c.getCreatedAt(),
                    traded ? Money.toDecimal(order.getFilledQuantity()) : null,
                    traded ? Money.toDecimal(Money.divide(order.getCumulativeQuoteQuantity(), order.getFilledQuantity())) : null,
                    traded ? Money.toDecimal(order.getCumulativeQuoteQuantity()) : null);
        }).toList();
    }

    public void addFunds(Long userId, UUID followId, FundsRequest request) {
        follows.addFunds(userId, followId, request.transferId(), Money.toWire(request.amount()));
    }

    public void removeFunds(Long userId, UUID followId, FundsRequest request) {
        follows.removeFunds(userId, followId, request.transferId(), Money.toWire(request.amount()));
    }

    public void stop(Long userId, UUID followId) {
        follows.stop(userId, followId);
    }

    public LeadPage lead(Long userId) {
        CopySettings set = settings();
        return new LeadPage(leaders.mine(userId).map(this::myLead).orElse(null), quoteAsset.symbol(),
                Money.toDecimal(set.getLeadMinBalance()), Money.toDecimal(set.getLeadMaxBalance()));
    }

    public MyLead apply(Long userId, ApplyRequest request, String ipAddress) {
        return myLead(new Listed(applications.apply(userId, request.displayName(), request.bio(), request.showPortfolio(),
                request.transferId(), Money.toWire(request.amount()), ipAddress), 0));
    }

    public void fundLead(Long userId, FundsRequest request) {
        leadAccount.fund(userId, request.transferId(), Money.toWire(request.amount()));
    }

    public void withdrawLead(Long userId, LeadWithdrawRequest request) {
        leadAccount.withdraw(userId, request.transferId(), assets.getBySymbol(request.asset()).getId(),
                Money.toWire(request.amount()));
    }

    public MyLead closeLead(Long userId) {
        closes.requireFlat(leadAccount.accountId(userId));
        return myLead(new Listed(leaders.close(userId), 0));
    }

    /** The gate and the code are the customer's; the key is the lead account's, and never withdraws. */
    public CreatedApiKeyView createLeadKey(Long userId, CreateSubAccountApiKeyRequest r, String ipAddress) {
        return CreatedApiKeyView.of(apiKeys.createForSubAccount(userId, leadAccount.accountId(userId), r.label(),
                r.canRead(), r.canTrade(), r.ipAllowlist(), r.expiresAt(), r.mfaCode(), ipAddress));
    }

    public List<ApiKeyView> leadKeys(Long userId) {
        return apiKeys.list(leadAccount.accountId(userId)).stream().map(key -> ApiKeyView.of(key, json)).toList();
    }

    public void revokeLeadKey(Long userId, UUID keyId, String ipAddress) {
        apiKeys.revoke(leadAccount.accountId(userId), keyId, ApiKeyRevokedReason.REVOKED_BY_USER, ipAddress);
    }

    private MyLead myLead(Listed listed) {
        CopyLeader l = listed.leader();
        List<Holding> held = l.getLeadUserId() == null ? List.of() : holdings(balances.list(l.getLeadUserId()));
        return new MyLead(l.getId(), l.getDisplayName(), l.getBio(), told(l.getStatus()), listed.followers(),
                l.getLeadUserId() != null, l.isShowPortfolio(), Money.toDecimal(shares.paidToLeader(l.getId())), held);
    }

    /** What the customer is told about their own leadership: never that staff stopped it, never why. */
    private static LeadState told(LeaderStatus status) {
        return switch (status) {
            case ACTIVE -> LeadState.ACTIVE;
            case SUSPENDED -> LeadState.UNAVAILABLE;
            case APPLIED, REJECTED, CLOSED -> LeadState.CLOSED;
        };
    }

    private List<Holding> holdings(List<Balance> held) {
        Map<Long, Asset> assetById = assets.listByIds(held.stream().map(Balance::getAssetId).distinct().toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return held.stream().filter(b -> b.getTotal() > 0)
                .map(b -> new Holding(assetById.get(b.getAssetId()).getSymbol(), Money.toDecimal(b.getAvailable()),
                        Money.toDecimal(b.getLocked()))).toList();
    }

    /** Two reads for any number of follows: every copy account's balances, and the leaders' names. */
    private List<Follow> views(Collection<CopyFollow> rows) {
        List<Balance> held = balances.list(rows.stream().map(CopyFollow::getCopyUserId).toList());
        Map<Long, Asset> assetById = assets.listByIds(held.stream().map(Balance::getAssetId).distinct().toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        Map<Long, List<Holding>> holdings = held.stream().filter(b -> b.getTotal() > 0)
                .collect(Collectors.groupingBy(Balance::getUserId, Collectors.mapping(
                        b -> new Holding(assetById.get(b.getAssetId()).getSymbol(), Money.toDecimal(b.getAvailable()),
                                Money.toDecimal(b.getLocked())), Collectors.toList())));
        Map<UUID, String> names = leaderRows.findAllById(rows.stream().map(CopyFollow::getLeaderId).distinct().toList())
                .stream().collect(Collectors.toMap(CopyLeader::getId, CopyLeader::getDisplayName));
        // Each coin's price once, however many accounts hold it; the copy asset is itself.
        Map<Long, Long> prices = new HashMap<>();
        Map<Long, Long> values = new HashMap<>();
        for (Balance b : held) {
            long price = prices.computeIfAbsent(b.getAssetId(), id -> id.equals(quoteAsset.id()) ? Money.SCALE_FACTOR
                    : markets.findByBaseAssetIdAndQuoteAssetId(id, quoteAsset.id())
                            .flatMap(m -> trades.lastBefore(m.getId(), Instant.now())).map(SpotTrade::getPrice).orElse(0L));
            values.merge(b.getUserId(), Money.multiply(price, b.getTotal()), Long::sum);
        }
        return rows.stream().map(f -> new Follow(f.getId(), f.getLeaderId(), names.get(f.getLeaderId()), f.getMode(),
                f.getAmountPerOrder() == null ? null : Money.toDecimal(f.getAmountPerOrder()), f.getSlippageCapBps(), f.getStatus(),
                told(f.getCloseReason()), f.getFailuresInRow(), f.getProfitShareBps(), Money.toDecimal(f.getRealizedProfit()),
                Money.toDecimal(f.getSharedProfit()), Money.toDecimal(values.getOrDefault(f.getCopyUserId(), 0L)),
                f.getCreatedAt(), f.getClosedAt(), holdings.getOrDefault(f.getCopyUserId(), List.of()))).toList();
    }

    /** A follower is told their leader stopped, not that staff stopped them: that is the leader's business. */
    private static CloseReason told(CloseReason reason) {
        return reason == CloseReason.LEADER_SUSPENDED ? CloseReason.LEADER_CLOSED : reason;
    }

    private static Leader leader(Listed listed, CopySettings set, CopyLeaderStats figures, List<SeriesPoint> line) {
        CopyLeader l = listed.leader();
        return new Leader(l.getId(), l.getDisplayName(), l.getBio(), listed.followers(),
                listed.followers() < set.getMaxFollowersPerLeader(), l.getCreatedAt(), l.isShowPortfolio(),
                l.profitShareBps(set), figures == null ? null : new Stats(window(figures.week()), window(figures.month()), window(figures.quarter()),
                        percent(figures.getCopySuccessBps()), Money.toDecimal(figures.getFundsCopying()),
                        line == null ? List.of() : line, figures.getComputedAt()));
    }

    private static Window window(CopyLeaderStats.Window w) {
        return new Window(percent(w.returnBps()), w.pnl() == null ? null : Money.toDecimal(w.pnl()), percent(w.maxDrawdownBps()));
    }

    /** Each leader's last thirty days as the card draws them: the chain of snapshots, as cumulative percentages. */
    private Map<UUID, List<SeriesPoint>> lines(Collection<UUID> leaderIds) {
        if (leaderIds.isEmpty()) {
            return Map.of();
        }
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(LINE_DAYS + 1L);
        Map<UUID, List<CopyLeaderSnapshot>> byLeader = new LinkedHashMap<>();
        for (CopyLeaderSnapshot s : snapshots.findByLeaderIdInAndDayGreaterThanEqualOrderByLeaderIdAscDayAsc(leaderIds, from)) {
            byLeader.computeIfAbsent(s.getLeaderId(), id -> new ArrayList<>()).add(s);
        }
        Map<UUID, List<SeriesPoint>> lines = new LinkedHashMap<>();
        byLeader.forEach((id, days) -> {
            List<BigDecimal> growth = ReturnMath.growth(days.stream()
                    .map(s -> new ReturnMath.Point(s.getEquity(), s.getNetTransfers())).toList());
            List<SeriesPoint> line = new ArrayList<>(days.size());
            for (int i = 0; i < days.size(); i++) {
                line.add(new SeriesPoint(days.get(i).getDay(), growth.get(i).subtract(BigDecimal.ONE).movePointRight(2)
                        .setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()));
            }
            lines.put(id, line);
        });
        return lines;
    }

    /** Basis points as a percentage, with no trailing zeros: 1234 is "12.34", -350 is "-3.5". */
    private static String percent(Integer bps) {
        return bps == null ? null : BigDecimal.valueOf(bps, 2).stripTrailingZeros().toPlainString();
    }

    private Terms terms(CopySettings set) {
        return new Terms(quoteAsset.symbol(), Money.toDecimal(set.getMinFixedRatioAmount()),
                Money.toDecimal(set.getMinFixedAmount()), set.getProfitShareBps(), set.getMaxCopiesPerUser(),
                set.getMaxFollowersPerLeader(), set.getFailureLimit(), Money.toDecimal(set.getLeadMinBalance()));
    }

    private CopySettings settings() {
        return settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
    }
}
