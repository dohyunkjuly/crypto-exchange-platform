package com.exchange.user.subaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.subaccount.exception.NotAMasterAccountException;
import com.exchange.user.subaccount.exception.SubAccountFrozenException;
import com.exchange.user.subaccount.exception.SubAccountLimitReachedException;
import com.exchange.user.subaccount.exception.SubAccountNotFoundException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubAccountServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private UserAuthEventRepository events;

    private SubAccountService service;
    private User master;
    private User sub;

    @BeforeEach
    void setUp() {
        service = new SubAccountService(users, events);
        master = user(1L, "ann@example.com", UserType.PRIMARY, null);
        sub = user(2L, "k3j9x2p1_virtual@wv7vw8elnq.com", UserType.SUB, 1L);
        when(users.findById(1L)).thenReturn(Optional.of(master));
        when(users.findById(2L)).thenReturn(Optional.of(sub));
        when(users.findByParentUserId(1L)).thenReturn(List.of(sub));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static User user(Long id, String email, UserType type, Long parent) {
        User user = new User(email, type == UserType.PRIMARY ? "hash" : null);
        ReflectionTestUtils.setField(user, "id", id);
        user.setType(type);
        user.setParentUserId(parent);
        return user;
    }

    @Test
    @DisplayName("a sub is a SUB row under its master, with a virtual address and no password")
    void create() {
        User made = service.create(1L, "arb bot", "ip");

        assertThat(made.getType()).isEqualTo(UserType.SUB);
        assertThat(made.getParentUserId()).isEqualTo(1L);
        assertThat(made.getTradeGroupId()).isEqualTo(1L);
        assertThat(made.getName()).isEqualTo("arb bot");
        assertThat(made.getPasswordHash()).as("nothing logs in as a sub").isNull();
        assertThat(made.isEmailVerified()).isTrue();

        // Random on both sides, and nothing of the master's in it: an identifier, not a mailbox.
        // The master's address, not its local part alone: eighteen random characters spell
        // "ann" about once in 2,600 runs, and CI found that one.
        assertThat(made.getEmail()).matches("[a-z0-9]{8}_virtual@[a-z0-9]{10}\\.com");
        assertThat(made.getEmail()).doesNotContain("ann@").doesNotContain("example.com");
        assertThat(service.create(1L, "second", "ip").getEmail()).isNotEqualTo(made.getEmail());
    }

    @Test
    @DisplayName("a sub cannot have subs, and a master cannot have more than the cap")
    void limits() {
        assertThatThrownBy(() -> service.create(2L, "nested", "ip")).isInstanceOf(NotAMasterAccountException.class);

        when(users.findByParentUserId(1L)).thenReturn(Collections.nCopies(SubAccountService.MAX_SUB_ACCOUNTS, sub));
        assertThatThrownBy(() -> service.create(1L, "one too many", "ip"))
                .isInstanceOf(SubAccountLimitReachedException.class);
    }

    @Test
    @DisplayName("a sub is reachable only through its own master")
    void ownership() {
        User other = user(3L, "bob@example.com", UserType.PRIMARY, null);
        when(users.findById(3L)).thenReturn(Optional.of(other));

        assertThat(service.get(1L, 2L)).isSameAs(sub);
        assertThatThrownBy(() -> service.get(3L, 2L)).isInstanceOf(SubAccountNotFoundException.class);
        assertThatThrownBy(() -> service.get(1L, 1L)).as("the master is not one of its own subs")
                .isInstanceOf(SubAccountNotFoundException.class);
    }

    @Test
    @DisplayName("a transfer may run between the master and its subs, but not to or from a frozen sub")
    void transferable() {
        assertThatCode(() -> service.requireTransferable(1L, 1L, 2L)).doesNotThrowAnyException();
        assertThatCode(() -> service.requireTransferable(1L, 2L, 1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireTransferable(1L, 1L, 99L)).isInstanceOf(SubAccountNotFoundException.class);

        service.setFrozen(1L, 2L, true, "ip");
        assertThat(sub.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> service.requireTransferable(1L, 1L, 2L)).isInstanceOf(SubAccountFrozenException.class);

        service.setFrozen(1L, 2L, false, "ip");
        assertThatCode(() -> service.requireTransferable(1L, 1L, 2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nothing moves in a family while trading is suspended on the master or on a side")
    void noTransferWhileSuspended() {
        master.setTradingSuspendedAt(Instant.now());
        assertThatThrownBy(() -> service.requireTransferable(1L, 1L, 2L)).isInstanceOf(TradingSuspendedException.class);
        master.setTradingSuspendedAt(null);

        // Otherwise a suspended sub's funds would move to a sibling that still trades.
        sub.setTradingSuspendedAt(Instant.now());
        assertThatThrownBy(() -> service.requireTransferable(1L, 2L, 1L)).isInstanceOf(TradingSuspendedException.class);
        assertThatThrownBy(() -> service.requireTransferable(1L, 1L, 2L)).isInstanceOf(TradingSuspendedException.class);
    }

    @Test
    @DisplayName("a lead or copy account is a sub of its own kind: outside the twenty, and never listed or fetched as an ordinary one")
    void copyTradingAccountsAreNotOrdinarySubs() {
        User copy = user(3L, "c_virtual@x.com", UserType.SUB, 1L);
        copy.setSubPurpose(SubPurpose.COPY);
        when(users.findById(3L)).thenReturn(Optional.of(copy));
        // Twenty copy accounts beside the one ordinary sub: the limit counts only the ordinary one.
        List<User> family = new java.util.ArrayList<>(Collections.nCopies(SubAccountService.MAX_SUB_ACCOUNTS, copy));
        family.add(sub);
        when(users.findByParentUserId(1L)).thenReturn(family);

        assertThat(service.list(1L)).containsExactly(sub);
        assertThatThrownBy(() -> service.get(1L, 3L)).isInstanceOf(SubAccountNotFoundException.class);
        assertThatThrownBy(() -> service.setFrozen(1L, 3L, true, "ip")).isInstanceOf(SubAccountNotFoundException.class);
        assertThatThrownBy(() -> service.requireTransferable(1L, 1L, 3L)).isInstanceOf(SubAccountNotFoundException.class);
        assertThat(service.create(1L, "second", "ip").getSubPurpose()).isEqualTo(SubPurpose.STANDARD);
        assertThat(service.familyIds(1L)).contains(3L);
    }

    @Test
    @DisplayName("a lead account is made for a master only, with its purpose, and never through the ordinary door")
    void makesACopyTradingAccount() {
        User lead = service.createForCopyTrading(1L, SubPurpose.LEAD, "lead", "ip");

        assertThat(lead.getType()).isEqualTo(UserType.SUB);
        assertThat(lead.getSubPurpose()).isEqualTo(SubPurpose.LEAD);
        assertThat(lead.getParentUserId()).isEqualTo(1L);
        assertThat(lead.getPasswordHash()).isNull();
        assertThatThrownBy(() -> service.createForCopyTrading(2L, SubPurpose.COPY, "x", "ip"))
                .isInstanceOf(NotAMasterAccountException.class);
        assertThatThrownBy(() -> service.createForCopyTrading(1L, SubPurpose.STANDARD, "x", "ip"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
