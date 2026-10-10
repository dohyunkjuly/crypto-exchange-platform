package com.exchange.spot.settlement.fee;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import com.exchange.fee.tier.FeeTier;
import com.exchange.fee.tier.FeeTierRepository;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places one family on the ladder from its trailing volume (docs/reference/fee-tiers.md).
 *
 * <p>The row is the master's, the volume is the whole family's: a sub pays its master's rate,
 * so what it trades earns the rate it pays. A family with nothing in the window lands on level
 * 0, which is how a VIP who stopped trading is demoted -- the caller revisits every tiered
 * master, not only those who traded.
 *
 * <p>Here rather than in the fee library because only this app assigns a tier; every other app
 * reads the row through {@code FeeQueryService}. {@link TierRefreshService} passes the family.
 */
@Service
public class UserFeeTierAssignmentService {

    private final UserDailyVolumeRepository volumes;
    private final UserFeeTierRepository userTiers;
    private final FeeTierRepository tiers;

    public UserFeeTierAssignmentService(UserDailyVolumeRepository volumes, UserFeeTierRepository userTiers,
                                        FeeTierRepository tiers) {
        this.volumes = volumes;
        this.userTiers = userTiers;
        this.tiers = tiers;
    }

    /**
     * @param family the master and every sub, the master included
     * @param from   the first day of the window, inclusive
     * @param to     the last day, inclusive -- yesterday, when run after midnight
     */
    @Transactional
    public UserFeeTier assign(Long masterId, Collection<Long> family, FeeProduct product,
                              LocalDate from, LocalDate to, Instant now) {
        long volume = volumes.sumVolume(family, product, from, to);
        int level = tiers.findHighestReached(product, volume).map(FeeTier::getLevel).orElse(0);
        UserFeeTier tier = userTiers.findByUserIdAndProduct(masterId, product)
                .orElseGet(() -> new UserFeeTier(masterId, product));
        tier.assign(level, volume, from, to, now);
        return userTiers.save(tier);
    }
}
