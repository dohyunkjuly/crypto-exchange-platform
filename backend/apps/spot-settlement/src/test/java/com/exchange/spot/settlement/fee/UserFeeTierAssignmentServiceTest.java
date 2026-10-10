package com.exchange.spot.settlement.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import com.exchange.fee.tier.FeeTier;
import com.exchange.fee.tier.FeeTierRepository;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A family climbs together, and a family that went quiet comes back down. */
class UserFeeTierAssignmentServiceTest {

    private static final long ONE = 100_000_000L;
    private static final LocalDate FROM = LocalDate.of(2026, 8, 28);
    private static final LocalDate TO = LocalDate.of(2026, 9, 26);

    private final UserDailyVolumeRepository volumes = Mockito.mock(UserDailyVolumeRepository.class);
    private final UserFeeTierRepository userTiers = Mockito.mock(UserFeeTierRepository.class);
    private final FeeTierRepository tiers = Mockito.mock(FeeTierRepository.class);
    private final UserFeeTierAssignmentService service = new UserFeeTierAssignmentService(volumes, userTiers, tiers);

    private final FeeTier vip1 = new FeeTier(FeeProduct.SPOT, 1, "VIP 1", 1_000_000 * ONE, 9, 10);

    @Test
    @DisplayName("the family's volume is summed onto the master's row, which is created if missing")
    void familyClimbsTogether() {
        List<Long> family = List.of(1L, 2L, 3L);
        Mockito.when(volumes.sumVolume(family, FeeProduct.SPOT, FROM, TO)).thenReturn(1_200_000 * ONE);
        Mockito.when(tiers.findHighestReached(FeeProduct.SPOT, 1_200_000 * ONE)).thenReturn(Optional.of(vip1));
        Mockito.when(userTiers.findByUserIdAndProduct(1L, FeeProduct.SPOT)).thenReturn(Optional.empty());
        Mockito.when(userTiers.save(any())).thenAnswer(i -> i.getArgument(0));

        Instant now = Instant.parse("2026-09-27T00:20:00Z");
        UserFeeTier tier = service.assign(1L, family, FeeProduct.SPOT, FROM, TO, now);

        assertThat(tier.getUserId()).isEqualTo(1L);
        assertThat(tier.getTierLevel()).isEqualTo(1);
        assertThat(tier.getVolume30d()).isEqualTo(1_200_000 * ONE);
        assertThat(tier.getWindowStart()).isEqualTo(FROM);
        assertThat(tier.getWindowEnd()).isEqualTo(TO);
        assertThat(tier.getComputedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("no volume in the window puts an existing VIP back on level 0, on the same row")
    void quietFamilyIsDemoted() {
        UserFeeTier existing = new UserFeeTier(1L, FeeProduct.SPOT);
        existing.assign(1, 1_200_000 * ONE, FROM.minusDays(1), TO.minusDays(1), Instant.EPOCH);
        Mockito.when(volumes.sumVolume(any(), eq(FeeProduct.SPOT), eq(FROM), eq(TO))).thenReturn(0L);
        Mockito.when(tiers.findHighestReached(FeeProduct.SPOT, 0L)).thenReturn(Optional.empty());
        Mockito.when(userTiers.findByUserIdAndProduct(1L, FeeProduct.SPOT)).thenReturn(Optional.of(existing));
        Mockito.when(userTiers.save(any())).thenAnswer(i -> i.getArgument(0));

        UserFeeTier tier = service.assign(1L, List.of(1L), FeeProduct.SPOT, FROM, TO, Instant.now());

        assertThat(tier).isSameAs(existing);
        assertThat(tier.getTierLevel()).isZero();
        assertThat(tier.getVolume30d()).isZero();
    }
}
