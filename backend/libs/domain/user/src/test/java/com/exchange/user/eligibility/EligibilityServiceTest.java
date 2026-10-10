package com.exchange.user.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.user.eligibility.exception.AccountFrozenException;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import java.util.Optional;
import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.user.eligibility.exception.MfaRequiredException;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** The order of the checks is the point: each one names the thing the user must do next. */
class EligibilityServiceTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final EligibilityService eligibility = new EligibilityService(users);

    @Test
    @DisplayName("an unverified email is refused before KYC or 2FA are even considered")
    void emailComesFirst() {
        User user = user(false, KycLevel.BASIC, true);
        assertThatThrownBy(() -> eligibility.requireCanTrade(user))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    @DisplayName("verified email but no KYC names KYC as the next step")
    void thenKyc() {
        assertThatThrownBy(() -> eligibility.requireCanTrade(user(true, KycLevel.NONE, true)))
                .isInstanceOf(KycRequiredException.class);
    }

    @Test
    @DisplayName("verified and KYC'd but no second factor names 2FA")
    void thenMfa() {
        assertThatThrownBy(() -> eligibility.requireCanTrade(user(true, KycLevel.BASIC, false)))
                .isInstanceOf(MfaRequiredException.class);
    }

    @Test
    @DisplayName("all three present may trade")
    void allThree() {
        assertThatCode(() -> eligibility.requireCanTrade(user(true, KycLevel.BASIC, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a sub-account is gated by its master: the master's KYC and 2FA count, and it holds none itself")
    void aSubAccountIsGatedByItsMaster() {
        User master = user(true, KycLevel.BASIC, true);
        Mockito.when(users.findById(7L)).thenReturn(Optional.of(master));
        User sub = new User("k3j9x2p1_virtual@wv7vw8elnq.com", null);
        sub.setType(UserType.SUB);
        sub.setParentUserId(7L);

        assertThatCode(() -> eligibility.requireCanTrade(sub)).doesNotThrowAnyException();
        assertThat(eligibility.canTrade(sub)).isTrue();

        master.setMfaEnabled(false);
        assertThatThrownBy(() -> eligibility.requireCanTrade(sub)).isInstanceOf(MfaRequiredException.class);
    }

    @Test
    @DisplayName("a sub is refused while its master is frozen, whatever the sub's own row says")
    void aSubOfAFrozenMasterIsRefused() {
        User master = user(true, KycLevel.BASIC, true);
        master.setStatus(UserStatus.SUSPENDED);
        Mockito.when(users.findById(7L)).thenReturn(Optional.of(master));
        User sub = new User("k3j9x2p1_virtual@wv7vw8elnq.com", null);
        sub.setType(UserType.SUB);
        sub.setParentUserId(7L);
        // The sub itself is ACTIVE: nothing wrote to its row, and that must not be enough.

        assertThatThrownBy(() -> eligibility.requireCanTrade(sub)).isInstanceOf(AccountFrozenException.class);
        assertThat(eligibility.canTrade(sub)).isFalse();
    }

    @Test
    @DisplayName("a frozen account is refused first, whatever its verification")
    void frozenIsRefusedFirst() {
        User user = user(true, KycLevel.BASIC, true);
        user.setStatus(UserStatus.SUSPENDED);

        assertThatThrownBy(() -> eligibility.requireCanTrade(user)).isInstanceOf(AccountFrozenException.class);
        assertThat(eligibility.canTrade(user)).isFalse();
    }

    @Test
    @DisplayName("an exchange account never trades, however verified its row looks")
    void aSystemAccountNeverTrades() {
        // Everything a customer needs, on a house row: the type is what refuses it.
        User house = user(true, KycLevel.ADVANCED, true);
        house.setType(UserType.SYSTEM);

        assertThatThrownBy(() -> eligibility.requireCanTrade(house)).isInstanceOf(AccountFrozenException.class);
        assertThat(eligibility.canTrade(house)).isFalse();
        assertThat(house.canLogIn()).as("nor does it ever hold a session").isFalse();
    }

    @Test
    @DisplayName("a suspension stops trading on a fully verified account, and is named as such")
    void suspendedTradingIsRefused() {
        User user = user(true, KycLevel.BASIC, true);
        user.setTradingSuspendedAt(Instant.now());

        assertThatThrownBy(() -> eligibility.requireCanTrade(user)).isInstanceOf(TradingSuspendedException.class);
        assertThatThrownBy(() -> eligibility.requireTradingNotSuspended(user)).isInstanceOf(TradingSuspendedException.class);
        assertThat(eligibility.canTrade(user)).isFalse();

        user.setTradingSuspendedAt(null);
        assertThatCode(() -> eligibility.requireCanTrade(user)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a master's suspension reaches its sub through the gate, with nothing written to the sub")
    void aSubOfASuspendedMasterIsRefused() {
        User master = user(true, KycLevel.BASIC, true);
        master.setTradingSuspendedAt(Instant.now());
        Mockito.when(users.findById(7L)).thenReturn(Optional.of(master));
        User sub = new User("k3j9x2p1_virtual@wv7vw8elnq.com", null);
        sub.setType(UserType.SUB);
        sub.setParentUserId(7L);

        assertThat(sub.isTradingSuspended()).isFalse();
        assertThatThrownBy(() -> eligibility.requireCanTrade(sub)).isInstanceOf(TradingSuspendedException.class);
        assertThat(eligibility.canTrade(sub)).isFalse();
    }

    private static User user(boolean emailVerified, KycLevel level, boolean mfa) {
        User user = new User("t@example.com", "hash");
        user.setEmailVerified(emailVerified);
        user.setKycLevel(level);
        user.setKycVerifiedUntil(level == KycLevel.NONE ? null : Instant.now().plusSeconds(86_400));
        user.setMfaEnabled(mfa);
        return user;
    }
}
