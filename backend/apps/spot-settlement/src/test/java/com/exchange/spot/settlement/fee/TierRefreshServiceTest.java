package com.exchange.spot.settlement.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Who gets placed: every family that traded, every master already tiered, and nobody twice. */
class TierRefreshServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 28);
    private static final LocalDate TO = LocalDate.of(2026, 9, 26);
    private static final Instant NOW = Instant.parse("2026-09-27T00:20:00Z");

    private final UserDailyVolumeRepository volumes = Mockito.mock(UserDailyVolumeRepository.class);
    private final UserFeeTierRepository userTiers = Mockito.mock(UserFeeTierRepository.class);
    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final UserFeeTierAssignmentService assignments = Mockito.mock(UserFeeTierAssignmentService.class);
    private final TierRefreshService service = new TierRefreshService(volumes, userTiers, users, assignments);

    @Test
    @DisplayName("a sub's trading places its master with the whole family; a quiet VIP and a synthetic account are placed too")
    void placesFamilies() {
        User master = user(1L, null);
        User tradedSub = user(2L, 1L);
        User quietSub = user(3L, 1L);
        // Traded: the sub, and an id with no account row (a benchmark's). Tiered: a master who did not trade.
        Mockito.when(volumes.findUserIdsWithVolumeSince(FeeProduct.SPOT, FROM)).thenReturn(List.of(2L, 99L));
        Mockito.when(users.findAllById(List.of(2L, 99L))).thenReturn(List.of(tradedSub));
        Mockito.when(users.findByParentUserId(any())).thenAnswer(i ->
                Long.valueOf(1L).equals(i.getArgument(0)) ? List.of(tradedSub, quietSub) : List.of());
        UserFeeTier quietVip = new UserFeeTier(7L, FeeProduct.SPOT);
        Mockito.when(userTiers.findByProduct(FeeProduct.SPOT)).thenReturn(List.of(quietVip, new UserFeeTier(1L, FeeProduct.SPOT)));

        int placed = service.refresh(FROM, TO, NOW);

        assertThat(placed).isEqualTo(3);
        Mockito.verify(assignments).assign(eq(1L), eq(List.of(1L, 2L, 3L)), eq(FeeProduct.SPOT), eq(FROM), eq(TO), eq(NOW));
        Mockito.verify(assignments).assign(eq(99L), eq(List.of(99L)), eq(FeeProduct.SPOT), eq(FROM), eq(TO), eq(NOW));
        Mockito.verify(assignments).assign(eq(7L), eq(List.of(7L)), eq(FeeProduct.SPOT), eq(FROM), eq(TO), eq(NOW));
        Mockito.verifyNoMoreInteractions(assignments);
        assertThat(master.getId()).isEqualTo(1L);
    }

    private static User user(long id, Long parentId) {
        User user = Mockito.spy(new User("u" + id + "@x.dev", null));
        Mockito.doReturn(id).when(user).getId();
        user.setParentUserId(parentId);
        return user;
    }
}
