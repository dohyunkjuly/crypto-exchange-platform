package com.exchange.admin.bff.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.apikey.ApiKeyRevocationService;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.session.SessionRevokedReason;
import com.exchange.user.session.UserSessionRepository;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.BalanceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The rules with teeth: a master's freeze takes its subs down and its thaw brings back only those,
 * and a trading suspension touches trading alone. Every change is audited; every refusal is one of
 * this app's codes.
 */
class UsersServiceTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
    private final AdminAuditService audit = Mockito.mock(AdminAuditService.class);
    private final UsersService service = new UsersService(users, sessions,
            Mockito.mock(ApiKeyRevocationService.class), Mockito.mock(BalanceService.class),
            Mockito.mock(AssetService.class), audit);

    private final User master = user(1L, UserType.PRIMARY, UserStatus.ACTIVE);
    private final User quiet = user(2L, UserType.SUB, UserStatus.ACTIVE);
    private final User alreadyFrozen = user(3L, UserType.SUB, UserStatus.SUSPENDED);

    @Test
    @DisplayName("freezing a master freezes its active subs, marks them as taken down with it, and logs it out everywhere")
    void freezeCascades() {
        alreadyFrozen.setFrozenReason("chargeback");
        stub();
        Mockito.when(sessions.revokeAllForUser(eq(1L), eq(SessionRevokedReason.REVOKED_BY_ADMIN), any())).thenReturn(2);

        UsersService.Outcome out = service.freeze(9L, 1L, "fraud review", "ip");

        assertThat(master.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(master.getFrozenReason()).isEqualTo("fraud review");
        assertThat(master.isFrozenWithMaster()).isFalse();
        assertThat(quiet.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(quiet.isFrozenWithMaster()).isTrue();
        // The sub that was frozen on its own keeps its own reason and is not counted.
        assertThat(alreadyFrozen.getFrozenReason()).isEqualTo("chargeback");
        assertThat(alreadyFrozen.isFrozenWithMaster()).isFalse();
        assertThat(out.subsAffected()).containsExactly(2L);
        assertThat(out.sessionsRevoked()).isEqualTo(2);
        Mockito.verify(audit).record(eq(9L), eq(AdminAction.USER_FROZEN), eq("user"), eq("1"), anyString(),
                Mockito.contains("fraud review"), eq("ip"));
    }

    @Test
    @DisplayName("unfreezing the master releases only the subs it took down; a sub frozen on its own stays frozen")
    void thawReleasesOnlyItsOwn() {
        master.setStatus(UserStatus.SUSPENDED);
        quiet.setStatus(UserStatus.SUSPENDED);
        quiet.setFrozenWithMaster(true);
        stub();

        UsersService.Outcome out = service.unfreeze(9L, 1L, "ip");

        assertThat(master.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(quiet.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(quiet.isFrozenWithMaster()).isFalse();
        assertThat(alreadyFrozen.getStatus()).as("frozen deliberately, not ours to release")
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(out.subsAffected()).containsExactly(2L);
        Mockito.verify(audit).record(eq(9L), eq(AdminAction.USER_UNFROZEN), eq("user"), eq("1"), anyString(),
                anyString(), eq("ip"));
    }

    @Test
    @DisplayName("the exchange's own accounts are not customers, and a second freeze is refused with nothing audited")
    void refusals() {
        User house = user(8L, UserType.SYSTEM, UserStatus.ACTIVE);
        Mockito.when(users.findById(8L)).thenReturn(Optional.of(house));
        assertThatThrownBy(() -> service.freeze(9L, 8L, "x", "ip"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdminErrorCode.USER_NOT_FOUND));

        master.setStatus(UserStatus.SUSPENDED);
        stub();
        assertThatThrownBy(() -> service.freeze(9L, 1L, "x", "ip"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdminErrorCode.ALREADY_IN_THAT_STATE));
        Mockito.verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("suspending trading keeps the account ACTIVE and its sessions, records why, and writes no sub row")
    void suspendAndResume() {
        stub();

        service.suspendTrading(9L, 1L, "wash trading review", "ip");
        assertThat(master.isTradingSuspended()).isTrue();
        assertThat(master.getTradingSuspendedReason()).isEqualTo("wash trading review");
        assertThat(master.getStatus()).as("it still logs in, reads and cancels").isEqualTo(UserStatus.ACTIVE);
        assertThatThrownBy(() -> service.suspendTrading(9L, 1L, "again", "ip"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdminErrorCode.ALREADY_IN_THAT_STATE));

        service.resumeTrading(9L, 1L, "ip");
        assertThat(master.isTradingSuspended()).isFalse();
        assertThat(master.getTradingSuspendedReason()).isNull();
        assertThatThrownBy(() -> service.resumeTrading(9L, 1L, "ip")).isInstanceOf(ApiException.class);

        Mockito.verify(users, Mockito.never()).findByParentUserId(any());
        Mockito.verifyNoInteractions(sessions);
        Mockito.verify(audit).record(eq(9L), eq(AdminAction.USER_TRADING_SUSPENDED), eq("user"), eq("1"), anyString(),
                Mockito.contains("wash trading review"), eq("ip"));
        Mockito.verify(audit).record(eq(9L), eq(AdminAction.USER_TRADING_RESUMED), eq("user"), eq("1"), anyString(),
                anyString(), eq("ip"));
    }

    @Test
    @DisplayName("logging out everywhere revokes every session and is audited")
    void revokeSessions() {
        stub();
        Mockito.when(sessions.revokeAllForUser(eq(1L), eq(SessionRevokedReason.REVOKED_BY_ADMIN), any())).thenReturn(3);

        assertThat(service.revokeSessions(9L, 1L, "ip")).isEqualTo(3);
        Mockito.verify(audit).record(eq(9L), eq(AdminAction.USER_SESSIONS_REVOKED), eq("user"), eq("1"), isNull(),
                Mockito.contains("3"), eq("ip"));
    }

    private void stub() {
        Mockito.when(users.findById(1L)).thenReturn(Optional.of(master));
        Mockito.when(users.findByParentUserId(1L)).thenReturn(List.of(quiet, alreadyFrozen));
        Mockito.when(users.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static User user(long id, UserType type, UserStatus status) {
        User user = Mockito.spy(new User("u" + id + "@x.dev", null));
        Mockito.doReturn(id).when(user).getId();
        user.setType(type);
        user.setStatus(status);
        if (type == UserType.SUB) {
            user.setParentUserId(1L);
        }
        return user;
    }
}
