package com.exchange.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.exchange.fee.FeeQueryService.UserFeeSummary;
import com.exchange.fee.override.UserFeeOverride;
import com.exchange.fee.override.UserFeeOverrideRepository;
import com.exchange.fee.tier.FeeTier;
import com.exchange.fee.tier.FeeTierRepository;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The three-part rule: an override beats a tier, an override only counts inside its window,
 * and a user with no tier row pays level 0.
 *
 * <p>Worth testing directly rather than through order placement, because these rates are now
 * shown to the user before they commit and charged to them afterwards. A resolution that is
 * wrong in one direction overcharges silently; wrong in the other, the exchange eats it.
 */
@ExtendWith(MockitoExtension.class)
class FeeQueryServiceTest {

    private static final long USER = 42L;

    @Mock
    private UserFeeOverrideRepository overrides;

    @Mock
    private UserFeeTierRepository userTiers;

    @Mock
    private FeeTierRepository tiers;

    private FeeQueryService service() {
        return new FeeQueryService(overrides, userTiers, tiers);
    }

    /** An override is a negotiated rate, so it wins outright -- and the tier table is not
     *  even consulted, which is what stops a stale tier row shadowing it. */
    @Test
    void anEffectiveOverrideBeatsTheTierEntirely() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT))
                .thenReturn(Optional.of(new UserFeeOverride(USER, FeeProduct.SPOT, -5, 3)));

        UserFeeSummary summary = service().summaryFor(USER, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(-5, 3));
        assertThat(summary.custom()).isTrue();
        verify(tiers, never()).findByProductAndLevel(eq(FeeProduct.SPOT), anyInt());
    }

    /** An override belongs to no published rung, so naming one would show a schedule that
     *  does not apply to this account. */
    @Test
    void anOverriddenUserHasNoTierToName() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT))
                .thenReturn(Optional.of(new UserFeeOverride(USER, FeeProduct.SPOT, -5, 3)));

        UserFeeSummary summary = service().summaryFor(USER, FeeProduct.SPOT);

        assertThat(summary.tierLevel()).isNull();
        assertThat(summary.tierName()).isNull();
    }

    /** An expired grant is not a rate. Honouring one would keep charging a promotional fee
     *  after the promotion, indefinitely and invisibly. */
    @Test
    void anExpiredOverrideIsIgnoredAndTheTierApplies() {
        UserFeeOverride expired = new UserFeeOverride(USER, FeeProduct.SPOT, -5, 3);
        ReflectionTestUtils.setField(expired, "expiresAt",
                Instant.now().minus(Duration.ofDays(1)));
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT))
                .thenReturn(Optional.of(expired));
        when(userTiers.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 0)).thenReturn(Optional.of(tier(0, "Base", -2, 10)));

        UserFeeSummary summary = service().summaryFor(USER, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(-2, 10));
        assertThat(summary.custom()).isFalse();
    }

    /** Nearly every user is in this state: no override, no tier row, so the published base
     *  rung applies. Getting the default wrong would misprice the whole exchange. */
    /** A sub-account pays exactly what its master pays: the master's override, and nothing of
     *  its own is even read. */
    @Test
    void aSubPaysItsMastersOverride() {
        long master = 7L;
        when(overrides.findByUserIdAndProduct(master, FeeProduct.SPOT))
                .thenReturn(Optional.of(new UserFeeOverride(master, FeeProduct.SPOT, 1, 4)));

        UserFeeSummary summary = service().summaryFor(USER, master, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(1, 4));
        assertThat(summary.custom()).isTrue();
        assertThat(service().ratesFor(USER, master, FeeProduct.SPOT)).isEqualTo(new FeeRates(1, 4));
        verify(overrides, never()).findByUserIdAndProduct(USER, FeeProduct.SPOT);
        verifyNoInteractions(userTiers);
    }

    /** With no override on the master, a sub pays the master's tier -- never one of its own. */
    @Test
    void aSubPaysItsMastersTier() {
        long master = 7L;
        when(overrides.findByUserIdAndProduct(master, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(userTiers.findByUserIdAndProduct(master, FeeProduct.SPOT)).thenReturn(Optional.of(userTier(3)));
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 3)).thenReturn(Optional.of(tier(3, "VIP 3", 7, 8)));

        UserFeeSummary summary = service().summaryFor(USER, master, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(7, 8));
        assertThat(summary.tierName()).isEqualTo("VIP 3");
        verify(userTiers, never()).findByUserIdAndProduct(USER, FeeProduct.SPOT);
    }

    @Test
    void aUserWithNoTierRowPaysTheBaseRung() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(userTiers.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 0)).thenReturn(Optional.of(tier(0, "Base", -2, 10)));

        UserFeeSummary summary = service().summaryFor(USER, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(-2, 10));
        assertThat(summary.tierLevel()).isZero();
        assertThat(summary.tierName()).isEqualTo("Base");
    }

    @Test
    void anAssignedTierIsUsedInsteadOfTheBaseRung() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(userTiers.findByUserIdAndProduct(USER, FeeProduct.SPOT))
                .thenReturn(Optional.of(userTier(3)));
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 3)).thenReturn(Optional.of(tier(3, "VIP 3", -4, 6)));

        UserFeeSummary summary = service().summaryFor(USER, FeeProduct.SPOT);

        assertThat(summary.rates()).isEqualTo(new FeeRates(-4, 6));
        assertThat(summary.tierName()).isEqualTo("VIP 3");
    }

    /** A missing base rung is a broken deployment, not a free trade. Defaulting to zero
     *  would charge nobody anything and nothing would say so. */
    @Test
    void aMissingTierFailsRatherThanChargingNothing() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(userTiers.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 0)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().summaryFor(USER, FeeProduct.SPOT))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The order path and the order form must never resolve differently -- so one is
     *  literally the other, and this pins that rather than trusting it. */
    @Test
    void ratesForIsTheSameResolutionAsSummaryFor() {
        when(overrides.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(userTiers.findByUserIdAndProduct(USER, FeeProduct.SPOT)).thenReturn(Optional.empty());
        when(tiers.findByProductAndLevel(FeeProduct.SPOT, 0)).thenReturn(Optional.of(tier(0, "Base", -2, 10)));

        FeeQueryService service = service();

        assertThat(service.ratesFor(USER, FeeProduct.SPOT))
                .isEqualTo(service.summaryFor(USER, FeeProduct.SPOT).rates());
    }

    private static FeeTier tier(int level, String name, int makerBps, int takerBps) {
        return new FeeTier(FeeProduct.SPOT, level, name, 0, makerBps, takerBps);
    }

    private static UserFeeTier userTier(int level) {
        UserFeeTier assigned = new UserFeeTier(USER, FeeProduct.SPOT);
        ReflectionTestUtils.setField(assigned, "tierLevel", level);
        return assigned;
    }
}
