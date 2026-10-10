package com.exchange.admin.bff.overrides;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.FeeGrant;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.FeeView;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.LimitGrant;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.LimitView;
import com.exchange.admin.bff.overrides.dto.UserOverrideViews.Overrides;
import com.exchange.admin.bff.users.UsersService;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.core.api.exception.ApiException;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.fee.FeeQueryService.UserFeeSummary;
import com.exchange.fee.override.UserFeeOverride;
import com.exchange.fee.override.UserFeeOverrideRepository;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimits;
import com.exchange.spot.limit.exchange.SpotExchangeOrderLimitsRepository;
import com.exchange.spot.limit.override.SpotUserOrderLimitOverride;
import com.exchange.spot.limit.override.SpotUserOrderLimitOverrideRepository;
import com.exchange.spot.limit.symbol.SpotSymbolUserOrderLimitOverride;
import com.exchange.spot.limit.symbol.SpotSymbolUserOrderLimitOverrideRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's exceptions to the exchange's defaults, set by staff (docs/reference/admin.md): their
 * own fee rate, their own cap on open orders across every market, and their own cap in one market.
 * Each carries the reason, who granted it and an optional expiry; the order path already honours
 * all three from the next order ({@code FeeQueryService}, {@code OrderService.checkOrderLimits}).
 *
 * <p><b>A fee override is set on a master only.</b> A sub-account pays exactly what its master
 * pays -- override or tier -- and has no fee of its own, so there is nothing to set on a sub.
 * Order limits stay per account: they stop one account flooding the book, and a sub is an account.
 *
 * <p>An order keeps the fee it was accepted with, so a new rate applies to new orders.
 */
@Service
public class UserOverridesService {

    /** A fee as staff set it: the rates in bps, why, and until when (null for no end). */
    public record Fee(int makerFeeBps, int takerFeeBps, String reason, Instant expiresAt) {
    }

    /** Caps across every market; either may be null, not both. */
    public record OrderLimit(Integer maxNumOrders, Integer maxNumAlgoOrders, String reason, Instant expiresAt) {
    }

    /** Caps in one market; either may be null, not both. */
    public record MarketLimit(String symbol, Integer maxOpenOrders, Integer maxOpenConditionalOrders, String reason,
                              Instant expiresAt) {
    }

    private final UsersService users;
    private final UserFeeOverrideRepository fees;
    private final SpotUserOrderLimitOverrideRepository orderLimits;
    private final SpotSymbolUserOrderLimitOverrideRepository marketLimits;
    private final SpotSymbolRepository markets;
    private final SpotExchangeOrderLimitsRepository exchangeLimits;
    private final FeeQueryService feeQuery;
    private final AdminUserRepository admins;
    private final AdminAuditService audit;

    public UserOverridesService(UsersService users, UserFeeOverrideRepository fees,
                                SpotUserOrderLimitOverrideRepository orderLimits,
                                SpotSymbolUserOrderLimitOverrideRepository marketLimits, SpotSymbolRepository markets,
                                SpotExchangeOrderLimitsRepository exchangeLimits, FeeQueryService feeQuery,
                                AdminUserRepository admins, AdminAuditService audit) {
        this.users = users;
        this.fees = fees;
        this.orderLimits = orderLimits;
        this.marketLimits = marketLimits;
        this.markets = markets;
        this.exchangeLimits = exchangeLimits;
        this.feeQuery = feeQuery;
        this.admins = admins;
        this.audit = audit;
    }

    /**
     * What the customer pays and may hold, and where each comes from. A sub pays its master's fee,
     * so the fee override shown for a sub is the master's; order limits are the account's own.
     */
    @Transactional(readOnly = true)
    public Overrides view(Long id) {
        User user = users.customer(id);
        Instant now = Instant.now();
        Long masterId = user.getParentUserId();
        // A sub pays its master's fee: the override shown is the master's.
        UserFeeOverride override = fees.findByUserIdAndProduct(masterId != null ? masterId : id, FeeProduct.SPOT)
                .orElse(null);
        SpotUserOrderLimitOverride orderLimit = orderLimits.findByUserId(id).orElse(null);
        List<SpotSymbolUserOrderLimitOverride> perMarket = marketLimits.findByUserId(id);
        Map<Long, String> grantors = admins.findAllById(Stream.of(
                        Stream.ofNullable(override).map(UserFeeOverride::getGrantedBy),
                        Stream.ofNullable(orderLimit).map(SpotUserOrderLimitOverride::getGrantedBy),
                        perMarket.stream().map(SpotSymbolUserOrderLimitOverride::getGrantedBy))
                .flatMap(Function.identity()).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(AdminUser::getId, AdminUser::getEmail));

        UserFeeSummary summary = feeQuery.summaryFor(id, masterId, FeeProduct.SPOT);
        FeeView fee = new FeeView(summary.rates().makerBps(), summary.rates().takerBps(),
                summary.custom() ? "OVERRIDE" : "TIER", summary.tierLevel(), summary.tierName(), masterId != null,
                fee(override, now, grantors));

        SpotExchangeOrderLimits defaults = exchangeLimits.findById(SpotExchangeOrderLimits.SINGLETON_ID).orElseThrow();
        LimitView account = new LimitView(null, defaults.getMaxNumOrders(), defaults.getMaxNumAlgoOrders(),
                orderLimit == null ? null : new LimitGrant(orderLimit.getMaxNumOrders(), orderLimit.getMaxNumAlgoOrders(),
                        orderLimit.getReason(), orderLimit.getExpiresAt(), orderLimit.isEffective(now),
                        grantors.get(orderLimit.getGrantedBy()), orderLimit.getUpdatedAt()));

        Map<Long, SpotSymbol> byId = markets.findAllById(perMarket.stream()
                        .map(SpotSymbolUserOrderLimitOverride::getSymbolId).toList())
                .stream().collect(Collectors.toMap(SpotSymbol::getId, Function.identity()));
        List<LimitView> marketViews = new ArrayList<>();
        for (SpotSymbolUserOrderLimitOverride row : perMarket) {
            SpotSymbol market = byId.get(row.getSymbolId());
            marketViews.add(new LimitView(market.getSymbol(), market.getMaxOpenOrders(),
                    market.getMaxOpenConditionalOrders(), new LimitGrant(row.getMaxOpenOrders(),
                    row.getMaxOpenConditionalOrders(), row.getReason(), row.getExpiresAt(), row.isEffective(now),
                    grantors.get(row.getGrantedBy()), row.getUpdatedAt())));
        }
        marketViews.sort(Comparator.comparing(LimitView::symbol));
        return new Overrides(fee, account, marketViews);
    }


    /** The same rule as the schedule's: never a negative taker fee, never a rebate above it. */
    @Transactional
    public UserFeeOverride setFee(Long by, Long userId, Fee fee, String ip) {
        User user = users.customer(userId);
        if (user.getType() == UserType.SUB) {
            throw new ApiException(AdminErrorCode.FEE_OVERRIDE_ON_SUB_ACCOUNT);
        }
        if (fee.takerFeeBps() < 0 || fee.takerFeeBps() >= 10_000) {
            throw invalid("the taker fee is 0 to 9999 bps");
        }
        if (fee.makerFeeBps() >= 10_000 || fee.makerFeeBps() < -fee.takerFeeBps()) {
            throw invalid("a maker rebate cannot exceed the taker fee");
        }
        future(fee.expiresAt());
        UserFeeOverride row = fees.findByUserIdAndProduct(userId, FeeProduct.SPOT).orElse(null);
        String was = row == null ? null : feeJson(row);
        if (row == null) {
            row = new UserFeeOverride(userId, FeeProduct.SPOT, fee.makerFeeBps(), fee.takerFeeBps());
        }
        row.setMakerFeeBps(fee.makerFeeBps());
        row.setTakerFeeBps(fee.takerFeeBps());
        row.setReason(fee.reason().trim());
        row.setExpiresAt(fee.expiresAt());
        row.setGrantedBy(by);
        row = fees.save(row);
        audit.record(by, AdminAction.USER_FEE_OVERRIDE_SET, "user", userId.toString(), was, feeJson(row), ip);
        return row;
    }

    @Transactional
    public void removeFee(Long by, Long userId, String ip) {
        users.customer(userId);
        UserFeeOverride row = fees.findByUserIdAndProduct(userId, FeeProduct.SPOT)
                .orElseThrow(() -> new ApiException(AdminErrorCode.OVERRIDE_NOT_FOUND));
        fees.delete(row);
        audit.record(by, AdminAction.USER_FEE_OVERRIDE_REMOVED, "user", userId.toString(), feeJson(row), null, ip);
    }

    @Transactional
    public SpotUserOrderLimitOverride setOrderLimit(Long by, Long userId, OrderLimit limit, String ip) {
        users.customer(userId);
        caps(limit.maxNumOrders(), limit.maxNumAlgoOrders());
        future(limit.expiresAt());
        SpotUserOrderLimitOverride row = orderLimits.findByUserId(userId).orElse(null);
        String was = row == null ? null : limitJson(row.getMaxNumOrders(), row.getMaxNumAlgoOrders(), row.getReason(),
                row.getExpiresAt());
        if (row == null) {
            row = new SpotUserOrderLimitOverride(userId, limit.maxNumOrders(), limit.maxNumAlgoOrders());
        }
        row.setMaxNumOrders(limit.maxNumOrders());
        row.setMaxNumAlgoOrders(limit.maxNumAlgoOrders());
        row.setReason(limit.reason().trim());
        row.setExpiresAt(limit.expiresAt());
        row.setGrantedBy(by);
        row = orderLimits.save(row);
        audit.record(by, AdminAction.USER_ORDER_LIMIT_SET, "user", userId.toString(), was,
                limitJson(row.getMaxNumOrders(), row.getMaxNumAlgoOrders(), row.getReason(), row.getExpiresAt()), ip);
        return row;
    }

    @Transactional
    public void removeOrderLimit(Long by, Long userId, String ip) {
        users.customer(userId);
        SpotUserOrderLimitOverride row = orderLimits.findByUserId(userId)
                .orElseThrow(() -> new ApiException(AdminErrorCode.OVERRIDE_NOT_FOUND));
        orderLimits.delete(row);
        audit.record(by, AdminAction.USER_ORDER_LIMIT_REMOVED, "user", userId.toString(),
                limitJson(row.getMaxNumOrders(), row.getMaxNumAlgoOrders(), row.getReason(), row.getExpiresAt()), null, ip);
    }

    @Transactional
    public SpotSymbolUserOrderLimitOverride setMarketLimit(Long by, Long userId, MarketLimit limit, String ip) {
        users.customer(userId);
        SpotSymbol market = market(limit.symbol());
        caps(limit.maxOpenOrders(), limit.maxOpenConditionalOrders());
        future(limit.expiresAt());
        SpotSymbolUserOrderLimitOverride row = marketLimits.findByUserIdAndSymbolId(userId, market.getId()).orElse(null);
        String was = row == null ? null : limitJson(row.getMaxOpenOrders(), row.getMaxOpenConditionalOrders(),
                row.getReason(), row.getExpiresAt());
        if (row == null) {
            row = new SpotSymbolUserOrderLimitOverride(userId, market.getId(), limit.maxOpenOrders(),
                    limit.maxOpenConditionalOrders());
        }
        row.setMaxOpenOrders(limit.maxOpenOrders());
        row.setMaxOpenConditionalOrders(limit.maxOpenConditionalOrders());
        row.setReason(limit.reason().trim());
        row.setExpiresAt(limit.expiresAt());
        row.setGrantedBy(by);
        row = marketLimits.save(row);
        audit.record(by, AdminAction.USER_MARKET_LIMIT_SET, "user", userId.toString(), was,
                "{\"market\":\"" + market.getSymbol() + "\",\"limit\":" + limitJson(row.getMaxOpenOrders(),
                        row.getMaxOpenConditionalOrders(), row.getReason(), row.getExpiresAt()) + "}", ip);
        return row;
    }

    @Transactional
    public void removeMarketLimit(Long by, Long userId, String symbol, String ip) {
        users.customer(userId);
        SpotSymbol market = market(symbol);
        SpotSymbolUserOrderLimitOverride row = marketLimits.findByUserIdAndSymbolId(userId, market.getId())
                .orElseThrow(() -> new ApiException(AdminErrorCode.OVERRIDE_NOT_FOUND));
        marketLimits.delete(row);
        audit.record(by, AdminAction.USER_MARKET_LIMIT_REMOVED, "user", userId.toString(),
                "{\"market\":\"" + market.getSymbol() + "\"}", null, ip);
    }

    private SpotSymbol market(String symbol) {
        return markets.findBySymbol(symbol == null ? "" : symbol.trim().toUpperCase())
                .orElseThrow(() -> new ApiException(AdminErrorCode.MARKET_NOT_FOUND));
    }

    /** What the table's checks demand, answered as a code rather than a constraint violation. */
    private static void caps(Integer orders, Integer stops) {
        if (orders == null && stops == null) {
            throw invalid("set at least one of the two caps");
        }
        if ((orders != null && orders < 1) || (stops != null && stops < 1)) {
            throw invalid("a cap is at least 1");
        }
        if (orders != null && stops != null && stops > orders) {
            throw invalid("the stop-order cap cannot be above the open-order cap");
        }
    }

    private static void future(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw invalid("the expiry is in the past");
        }
    }

    private static ApiException invalid(String why) {
        return new ApiException(AdminErrorCode.INVALID_OVERRIDE, why);
    }

    private static String feeJson(UserFeeOverride row) {
        return "{\"makerFeeBps\":" + row.getMakerFeeBps() + ",\"takerFeeBps\":" + row.getTakerFeeBps()
                + ",\"reason\":" + json(row.getReason()) + ",\"expiresAt\":" + json(row.getExpiresAt()) + "}";
    }

    private static String limitJson(Integer orders, Integer stops, String reason, Instant expiresAt) {
        return "{\"orders\":" + orders + ",\"stops\":" + stops + ",\"reason\":" + json(reason)
                + ",\"expiresAt\":" + json(expiresAt) + "}";
    }

    private static String json(Object value) {
        return value == null ? "null"
                : "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static FeeGrant fee(UserFeeOverride row, Instant now, Map<Long, String> grantors) {
        return row == null ? null : new FeeGrant(row.getMakerFeeBps(), row.getTakerFeeBps(), row.getReason(),
                row.getExpiresAt(), row.isEffective(now), grantors.get(row.getGrantedBy()), row.getUpdatedAt());
    }
}
