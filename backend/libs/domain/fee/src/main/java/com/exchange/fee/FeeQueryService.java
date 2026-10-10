package com.exchange.fee;

import com.exchange.fee.override.UserFeeOverride;
import com.exchange.fee.override.UserFeeOverrideRepository;
import com.exchange.fee.tier.FeeTier;
import com.exchange.fee.tier.FeeTierRepository;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a user pays, resolved once and used by everything that needs to know.
 *
 * <p><b>The reason this is a class rather than four lines in each caller.</b> The rule has
 * three parts -- an override beats a tier, an override only counts inside its effective
 * window, and a user with no tier row pays level 0 -- and two places need the answer: the
 * order path stamps it onto an order at acceptance, and the client shows it on the order
 * form before anyone commits money. If those two computed it separately, the form could
 * display one rate while the fill charged another, and nothing would fail: the user would
 * find out from a statement, if ever.
 *
 * <p>{@link #ratesFor} is therefore a thin wrapper over {@link #summaryFor} rather than a
 * second implementation. The hot path pays one small allocation for the guarantee that there
 * is exactly one resolution in this codebase.
 *
 * <p>Lives in {@code libs/domain/fee} because spot-open-api needs the same answer for its
 * own account endpoint -- the "does more than one app need it?" test in AGENTS.md.
 */
@Service
@Transactional(readOnly = true)
public class FeeQueryService {

    /** What a user with no tier row of their own pays: the published base rung. */
    private static final int BASE_TIER_LEVEL = 0;

    private final UserFeeOverrideRepository overrides;
    private final UserFeeTierRepository userTiers;
    private final FeeTierRepository tiers;

    public FeeQueryService(UserFeeOverrideRepository overrides, UserFeeTierRepository userTiers,
                           FeeTierRepository tiers) {
        this.overrides = overrides;
        this.userTiers = userTiers;
        this.tiers = tiers;
    }

    /**
     * Refuses to start without a base rung: every user resolves through level 0, so an
     * exchange with an empty {@code fee_tiers} cannot take an order. Caught here, with the
     * fix in the message, rather than at the first order. The schedule is seeded in dev and
     * test (db/seed); a deployment publishes its own.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyBaseTier() {
        if (tiers.findByProductAndLevel(FeeProduct.SPOT, BASE_TIER_LEVEL).isEmpty()) {
            throw new IllegalStateException("fee_tiers has no SPOT level 0 row, so no order could "
                    + "be priced. Publish a fee schedule (db/seed/R__fee_schedule.sql is the dev "
                    + "one), then start again.");
        }
    }

    /** The rates alone -- what the order path stamps onto an order. */
    public FeeRates ratesFor(Long userId, FeeProduct product) {
        return summaryFor(userId, null, product).rates();
    }

    /** As {@link #ratesFor(Long, FeeProduct)}, for a sub-account: see {@link #summaryFor(Long, Long, FeeProduct)}. */
    public FeeRates ratesFor(Long userId, Long masterId, FeeProduct product) {
        return summaryFor(userId, masterId, product).rates();
    }

    /**
     * The rates and where they came from.
     *
     * @throws IllegalStateException if the product has no base tier configured. A missing
     *         level 0 is a broken deployment rather than a user problem -- every user
     *         resolves through it, so silently charging zero would be worse than failing.
     */
    public UserFeeSummary summaryFor(Long userId, FeeProduct product) {
        return summaryFor(userId, null, product);
    }

    /**
     * A sub-account pays exactly what its master pays: the master's override, else the master's
     * tier. It has no fee of its own, so a customer's rate is the same whichever of their
     * accounts trades. The caller passes the master because this module does not read accounts.
     *
     * @param masterId the account's master, or null for a master
     */
    public UserFeeSummary summaryFor(Long userId, Long masterId, FeeProduct product) {
        Instant now = Instant.now();
        Long payer = masterId != null ? masterId : userId;

        Optional<UserFeeOverride> override = overrides.findByUserIdAndProduct(payer, product)
                .filter(granted -> granted.isEffective(now));
        if (override.isPresent()) {
            // A negotiated rate: there is no tier to name, and pretending there is one would
            // put a stranger's schedule in front of someone it does not apply to.
            return new UserFeeSummary(override.get().rates(), null, null, true);
        }

        int level = userTiers.findByUserIdAndProduct(payer, product)
                .map(UserFeeTier::getTierLevel)
                .orElse(BASE_TIER_LEVEL);
        FeeTier tier = tiers.findByProductAndLevel(product, level)
                .orElseThrow(() -> new IllegalStateException(
                        "no " + product + " fee tier configured for level " + level));
        return new UserFeeSummary(tier.rates(), tier.getLevel(), tier.getName(), false);
    }

    /**
     * A user's resolved fee position.
     *
     * @param tierLevel  null when {@code custom}, because an override belongs to no rung
     * @param tierName   null for the same reason
     * @param custom     true when a per-user override is in force rather than the schedule
     */
    public record UserFeeSummary(FeeRates rates, Integer tierLevel, String tierName,
                                 boolean custom) {
    }
}
