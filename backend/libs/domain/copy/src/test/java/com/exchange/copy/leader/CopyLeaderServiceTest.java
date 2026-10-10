package com.exchange.copy.leader;

import com.exchange.copy.leader.CopyLeaderService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.exception.AlreadyLeaderException;
import com.exchange.copy.leader.exception.DisplayNameTakenException;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.leader.exception.LeaderStatusException;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.subaccount.SubAccountService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A customer leads at once, staff can stop them, and each state allows only what follows from it. */
class CopyLeaderServiceTest {

    private static final long CUSTOMER = 1L;
    private static final long ADMIN = 9L;
    private static final long LEAD_ACCOUNT = 5L;

    private final CopyLeaderRepository leaders = Mockito.mock(CopyLeaderRepository.class);
    private final CopyFollowRepository follows = Mockito.mock(CopyFollowRepository.class);
    private final SubAccountService subAccounts = Mockito.mock(SubAccountService.class);
    private final CopyLeaderService service = new CopyLeaderService(leaders, follows, subAccounts);

    private final CopyLeader leader = new CopyLeader(Ulid.randomUuid(), CUSTOMER, "Mina", "trades the majors");

    @BeforeEach
    void setUp() {
        Mockito.when(leaders.save(any())).thenAnswer(call -> call.getArgument(0));
        Mockito.when(leaders.findById(leader.getId())).thenReturn(Optional.of(leader));
        User account = Mockito.mock(User.class);
        Mockito.when(account.getId()).thenReturn(LEAD_ACCOUNT);
        Mockito.when(subAccounts.createForCopyTrading(eq(CUSTOMER), eq(SubPurpose.LEAD), any(), any())).thenReturn(account);
    }

    @Test
    @DisplayName("applying leads at once, with a trimmed name and a lead account of its own")
    void applies() {
        Mockito.when(leaders.findByUserId(CUSTOMER)).thenReturn(Optional.empty());

        CopyLeader applied = service.apply(CUSTOMER, "  Mina  ", "  ", "ip");

        assertThat(applied.getStatus()).isEqualTo(LeaderStatus.ACTIVE);
        assertThat(applied.getDisplayName()).isEqualTo("Mina");
        assertThat(applied.getBio()).isNull();
        assertThat(applied.getLeadUserId()).isEqualTo(LEAD_ACCOUNT);
        Mockito.verify(subAccounts).createForCopyTrading(CUSTOMER, SubPurpose.LEAD, "Mina", "ip");
    }

    @Test
    @DisplayName("a name is 3 to 40 characters and belongs to one leader")
    void names() {
        Mockito.when(leaders.findByUserId(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.apply(CUSTOMER, "ab", null, "ip")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.apply(CUSTOMER, "x".repeat(41), null, "ip")).isInstanceOf(IllegalArgumentException.class);

        CopyLeader other = new CopyLeader(Ulid.randomUuid(), 2L, "Joon", null);
        Mockito.when(leaders.findByDisplayName("Joon")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Joon", null, "ip")).isInstanceOf(DisplayNameTakenException.class);
    }

    @Test
    @DisplayName("one row a customer: applying again is refused while leading or suspended, and once closed "
            + "reuses the row and the lead account")
    void appliesAgain() {
        leader.setLeadUserId(LEAD_ACCOUNT);
        Mockito.when(leaders.findByUserId(CUSTOMER)).thenReturn(Optional.of(leader));
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Mina", null, "ip")).isInstanceOf(AlreadyLeaderException.class);
        leader.setStatus(LeaderStatus.SUSPENDED);
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Mina", null, "ip")).isInstanceOf(AlreadyLeaderException.class);

        leader.setStatus(LeaderStatus.CLOSED);
        leader.setReviewedBy(ADMIN);
        leader.setReviewReason("wash trading");
        CopyLeader again = service.apply(CUSTOMER, "Mina K", "second try", "ip");

        assertThat(again).isSameAs(leader);
        assertThat(again.getStatus()).isEqualTo(LeaderStatus.ACTIVE);
        assertThat(again.getDisplayName()).isEqualTo("Mina K");
        assertThat(again.getReviewReason()).as("an old decision does not follow the new leading").isNull();
        Mockito.verifyNoInteractions(subAccounts);
    }

    @Test
    @DisplayName("staff suspend what is active and reinstate what is suspended -- nothing else")
    void staffDecisions() {
        assertThat(service.suspend(ADMIN, leader.getId(), "wash trading").getStatus()).isEqualTo(LeaderStatus.SUSPENDED);
        assertThatThrownBy(() -> service.suspend(ADMIN, leader.getId(), "again")).isInstanceOf(LeaderStatusException.class);

        assertThat(service.reinstate(ADMIN, leader.getId()).getStatus()).isEqualTo(LeaderStatus.ACTIVE);
        assertThatThrownBy(() -> service.reinstate(ADMIN, leader.getId())).isInstanceOf(LeaderStatusException.class);
        assertThatThrownBy(() -> service.suspend(ADMIN, Ulid.randomUuid(), "x")).isInstanceOf(LeaderNotFoundException.class);
    }

    @Test
    @DisplayName("only an active leader can stop leading, and only its own customer")
    void closes() {
        Mockito.when(leaders.findByUserId(CUSTOMER)).thenReturn(Optional.of(leader));
        leader.setStatus(LeaderStatus.SUSPENDED);
        assertThatThrownBy(() -> service.close(CUSTOMER)).as("suspended").isInstanceOf(LeaderStatusException.class);
        assertThatThrownBy(() -> service.close(77L)).isInstanceOf(NotALeaderException.class);

        leader.setStatus(LeaderStatus.ACTIVE);
        assertThat(service.close(CUSTOMER).getStatus()).isEqualTo(LeaderStatus.CLOSED);
    }

    @Test
    @DisplayName("only active leaders are shown to the public")
    void publicList() {
        leader.setStatus(LeaderStatus.SUSPENDED);
        assertThatThrownBy(() -> service.getActive(leader.getId())).isInstanceOf(LeaderNotFoundException.class);
        leader.setStatus(LeaderStatus.ACTIVE);
        assertThat(service.getActive(leader.getId()).leader()).isSameAs(leader);
    }
}
