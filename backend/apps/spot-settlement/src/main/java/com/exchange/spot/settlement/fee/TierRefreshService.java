package com.exchange.spot.settlement.fee;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Which families the nightly job places, and the family each one is (docs/reference/fee-tiers.md).
 *
 * <p>Two sets of accounts need a look: anyone who traded in the window, so a climber is
 * promoted, and every master with a tier row, so a family that went quiet is demoted rather
 * than keeping its rate forever. Each account resolves to its master, and the master's family
 * is itself plus every sub, whether or not the sub traded.
 *
 * <p>An account without a {@code user_users} row -- a benchmark's synthetic id -- is its own
 * master with no subs, so its trades still count for what they are.
 */
@Service
public class TierRefreshService {

    private final UserDailyVolumeRepository volumes;
    private final UserFeeTierRepository userTiers;
    private final UserRepository users;
    private final UserFeeTierAssignmentService assignments;

    public TierRefreshService(UserDailyVolumeRepository volumes, UserFeeTierRepository userTiers,
                              UserRepository users, UserFeeTierAssignmentService assignments) {
        this.volumes = volumes;
        this.userTiers = userTiers;
        this.users = users;
        this.assignments = assignments;
    }

    /** @return how many masters were placed */
    public int refresh(LocalDate from, LocalDate to, Instant now) {
        Set<Long> masters = new LinkedHashSet<>();
        List<Long> traded = volumes.findUserIdsWithVolumeSince(FeeProduct.SPOT, from);
        Set<Long> withRow = new LinkedHashSet<>();
        for (User user : users.findAllById(traded)) {
            withRow.add(user.getId());
            masters.add(user.getParentUserId() == null ? user.getId() : user.getParentUserId());
        }
        for (Long id : traded) {
            if (!withRow.contains(id)) {
                masters.add(id);
            }
        }
        for (UserFeeTier tier : userTiers.findByProduct(FeeProduct.SPOT)) {
            masters.add(tier.getUserId());
        }

        for (Long master : masters) {
            List<Long> family = new ArrayList<>();
            family.add(master);
            users.findByParentUserId(master).forEach(sub -> family.add(sub.getId()));
            assignments.assign(master, family, FeeProduct.SPOT, from, to, now);
        }
        return masters.size();
    }
}
