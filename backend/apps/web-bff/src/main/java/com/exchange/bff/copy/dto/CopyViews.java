package com.exchange.bff.copy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.exchange.copy.share.ShareTrigger;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.spot.order.Side;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Copy trading's shapes on the wire. Amounts are decimal strings (docs/reference/money.md). */
public final class CopyViews {

    private static final String AMOUNT = "\\d+(\\.\\d+)?";

    private CopyViews() {
    }

    /** What following costs and needs, shown before anyone commits money. */
    public record Terms(String quoteAsset, String minFixedRatioAmount, String minFixedAmount, int profitShareBps,
                        int maxCopies, int maxFollowers, int failureLimit, String leadMinBalance) {
    }

    /** A leader as followers see one: never an email address or a customer id. */
    /** {@code profitShareBps}: what following this leader now costs -- the leader's own rate, or the exchange's. */
    public record Leader(UUID id, String displayName, String bio, long followers, boolean accepting, Instant since,
                         boolean portfolioShown, int profitShareBps, Stats stats) {
    }

    /**
     * A leader's figures as of the last nightly run; the whole thing is null before the first.
     * Percentages are decimal strings ("12.34", "-3.5"), amounts are in the copy asset, and each
     * is null until there is something to measure. {@code series} is the last thirty days'
     * cumulative return, one percentage a snapshot, oldest first and starting at zero: the line
     * on the card.
     */
    public record Stats(Window days7, Window days30, Window days90, String copySuccessRate, String fundsCopying,
                        List<SeriesPoint> series, Instant asOf) {
    }

    /** One snapshot on the line: the UTC day it closed, and the cumulative return by then, a percentage. */
    public record SeriesPoint(LocalDate day, String value) {
    }

    /** One window: the return, what was earned, the deepest fall from a high. */
    public record Window(@JsonProperty("return") String return_, String pnl, String maxDrawdown) {
    }

    public record Leaders(List<Leader> leaders, Terms terms) {
    }

    /**
     * {@code portfolio} is null when the leader keeps it private; the figures above it never are.
     * {@code leadingBalance} is the lead account as the last nightly snapshot valued it, in the
     * copy asset; null before the first.
     */
    public record LeaderProfile(Leader leader, Terms terms, String leadingBalance, Portfolio portfolio) {
    }

    /** What the lead account holds and has lately traded. {@code value} is at the market's last trade. */
    /** What the lead account holds; its trades are paged on their own ({@code /trades}). */
    public record Portfolio(List<Position> holdings) {
    }

    public record Position(String asset, String amount, String value) {
    }

    public record LeadTrade(Instant time, String symbol, Side side, String price, String quantity, String quoteQuantity) {
    }

    /** The leader's switch: whether the profile shows the lead account's holdings and trades. */
    public record PortfolioRequest(boolean shown) {
    }

    /** {@code followId} is the client's, so a request sent twice is one follow. */
    public record FollowRequest(@NotNull UUID followId, @NotNull UUID leaderId, @NotNull CopyMode mode,
                                @Pattern(regexp = AMOUNT) String amountPerOrder,
                                @NotNull @Min(CopyFollow.MIN_SLIPPAGE_CAP_BPS) @Max(CopyFollow.MAX_SLIPPAGE_CAP_BPS)
                                Integer slippageCapBps,
                                @NotBlank @Pattern(regexp = AMOUNT) String amount) {
    }

    /** {@code transferId} is the client's, so a request sent twice moves the money once. */
    public record FundsRequest(@NotNull UUID transferId, @NotBlank @Pattern(regexp = AMOUNT) String amount) {
    }

    public record Holding(String asset, String available, String locked) {
    }

    /**
     * {@code profitShareBps} is the rate this account agreed to, frozen when it started.
     * {@code realizedProfit} is signed and net of fees; {@code sharedProfit} is what the leader has
     * been paid of it. Both in the copy asset, as of the last settlement. {@code value} is what the
     * account holds in the copy asset, each coin at its market's last trade -- shown, never moved on.
     */
    public record Follow(UUID id, UUID leaderId, String leaderName, CopyMode mode, String amountPerOrder,
                         int slippageCapBps, FollowStatus status, CloseReason closeReason, int failuresInRow, int profitShareBps,
                         String realizedProfit, String sharedProfit, String value, Instant createdAt, Instant closedAt,
                         List<Holding> holdings) {
    }

    /** One step: who, what they show, and the money that moves into the lead account ({@code transferId} is the client's). */
    public record ApplyRequest(@NotBlank @Size(min = 3, max = 40) String displayName, @Size(max = 500) String bio,
                               boolean showPortfolio, @NotNull UUID transferId,
                               @NotBlank @Pattern(regexp = AMOUNT) String amount) {
    }

    /** {@code asset} is a symbol; while leading, only the quote asset comes out. */
    public record LeadWithdrawRequest(@NotNull UUID transferId, @NotBlank String asset,
                                      @NotBlank @Pattern(regexp = AMOUNT) String amount) {
    }

    /**
     * The customer's own leadership. {@code status} is what they are told, not the row's: a
     * suspension is staff's and is never named to the customer. {@code hasAccount}: a lead account exists, whatever became
     * of the leading -- it is theirs to trade and to empty.
     */
    public record MyLead(UUID id, String displayName, String bio, LeadState status, long followers,
                         boolean hasAccount, boolean portfolioShown, String earned, List<Holding> holdings) {
    }

    public enum LeadState { ACTIVE, UNAVAILABLE, CLOSED }

    /** {@code lead} is null for a customer who has never applied. */
    public record LeadPage(MyLead lead, String quoteAsset, String minBalance, String maxBalance) {
    }

    /**
     * One copy, or one closing sale ({@code closing}). {@code orderId} is null when nothing was
     * placed. {@code filled} (base), {@code averagePrice} and {@code total} (quote) are what its
     * order traded, as settlement has written it; null until something has.
     */
    public record CopyLine(UUID id, String symbol, Side side, CopyOrderStatus status, CopyOrderReason reason,
                           UUID orderId, boolean closing, Instant createdAt, String filled, String averagePrice,
                           String total) {
    }

    /** One settlement of the profit share: what was due then and what the account could pay. */
    public record ShareLine(UUID id, ShareTrigger trigger, String due, String paid, Instant createdAt) {
    }

    /** The account; its copies and its profit-share settlements are paged on their own
     *  ({@code /copies}, {@code /shares}). */
    public record FollowDetail(Follow follow) {
    }
}
