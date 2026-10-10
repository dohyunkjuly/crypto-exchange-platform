package com.exchange.copy.follow;

import com.exchange.copy.follow.CopyFollowService;
import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.settings.CopyQuoteAsset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.common.id.Ulid;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
import com.exchange.copy.follow.exception.AlreadyFollowingException;
import com.exchange.copy.follow.exception.CopyAmountTooSmallException;
import com.exchange.copy.follow.exception.CopyBalanceBelowMinimumException;
import com.exchange.copy.follow.exception.CopyLimitReachedException;
import com.exchange.copy.follow.exception.FollowNotActiveException;
import com.exchange.copy.follow.exception.FollowNotFoundException;
import com.exchange.copy.leader.exception.LeaderNotAcceptingException;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.follow.exception.SelfFollowException;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Who may follow whom, with how much, and what the owner of a copy account may still do to it. */
class CopyFollowServiceTest {

    private static final long ONE = 100_000_000L;
    private static final long FOLLOWER = 1L;
    private static final long LEADER_USER = 2L;
    private static final long ACCOUNT = 3L;
    private static final long USDT = 9L;

    private final CopyFollowRepository follows = Mockito.mock(CopyFollowRepository.class);
    private final CopyLeaderRepository leaders = Mockito.mock(CopyLeaderRepository.class);
    private final CopySettingsRepository settings = Mockito.mock(CopySettingsRepository.class);
    private final SubAccountService subAccounts = Mockito.mock(SubAccountService.class);
    private final TransferService transfers = Mockito.mock(TransferService.class);
    private final BalanceRepository balances = Mockito.mock(BalanceRepository.class);
    private final CopyCloseService closes = Mockito.mock(CopyCloseService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopyProfitShareService shares = Mockito.mock(CopyProfitShareService.class);
    private final CopyFollowService service = new CopyFollowService(follows, leaders, settings, subAccounts,
            transfers, balances, closes, shares, new CopyQuoteAsset(assets, "USDT"));

    private final CopyLeader leader = new CopyLeader(Ulid.randomUuid(), LEADER_USER, "Mina", null);
    private final UUID followId = Ulid.randomUuid();

    @BeforeEach
    void setUp() {
        leader.setStatus(LeaderStatus.ACTIVE);
        Mockito.when(leaders.findById(leader.getId())).thenReturn(Optional.of(leader));
        CopySettings row = Mockito.mock(CopySettings.class);
        Mockito.when(row.getMaxFollowersPerLeader()).thenReturn(300);
        Mockito.when(row.getMaxCopiesPerUser()).thenReturn(10);
        Mockito.when(row.getMinFixedRatioAmount()).thenReturn(100 * ONE);
        Mockito.when(row.getMinFixedAmount()).thenReturn(10 * ONE);
        Mockito.when(row.getProfitShareBps()).thenReturn(1000);
        Mockito.when(settings.findById(CopySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        User account = Mockito.mock(User.class);
        Mockito.when(account.getId()).thenReturn(ACCOUNT);
        Mockito.when(subAccounts.createForCopyTrading(eq(FOLLOWER), eq(SubPurpose.COPY), any(), any())).thenReturn(account);
        Mockito.when(follows.save(any())).thenAnswer(call -> call.getArgument(0));
        Mockito.when(follows.findByUserIdOrderByCreatedAtDesc(FOLLOWER)).thenReturn(List.of());
    }

    @Test
    @DisplayName("following makes the copy account, the follow, and funds it under the follow's own id")
    void follows() {
        CopyFollow follow = service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, 50 * ONE, 50,
                500 * ONE, "ip");

        assertThat(follow.getCopyUserId()).isEqualTo(ACCOUNT);
        assertThat(follow.getLeaderId()).isEqualTo(leader.getId());
        assertThat(follow.getAmountPerOrder()).as("a fixed ratio carries no amount per order").isNull();
        Mockito.verify(subAccounts).createForCopyTrading(FOLLOWER, SubPurpose.COPY, "Mina", "ip");
        Mockito.verify(transfers).transfer(followId, FOLLOWER, ACCOUNT, USDT, 500 * ONE, FOLLOWER);
    }

    @Test
    @DisplayName("the follower's own slippage cap is kept on the follow, and only within 0.1% to 5%")
    void slippageCap() {
        CopyFollow follow = service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null, 120,
                500 * ONE, "ip");
        assertThat(follow.getSlippageCapBps()).isEqualTo(120);

        assertThatThrownBy(() -> service.follow(Ulid.randomUuid(), FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null,
                9, 500 * ONE, "ip")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.follow(Ulid.randomUuid(), FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null,
                501, 500 * ONE, "ip")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a follow takes the leader's own profit share when staff set one, the exchange's otherwise, and keeps it")
    void profitShareRate() {
        assertThat(service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null, 50, 500 * ONE, "ip")
                .getProfitShareBps()).isEqualTo(1000);

        leader.setProfitShareBps(1500);
        assertThat(service.follow(Ulid.randomUuid(), FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null, 50, 500 * ONE, "ip")
                .getProfitShareBps()).isEqualTo(1500);
    }

    @Test
    @DisplayName("the same request again is the same follow: no second account, no second transfer")
    void followTwice() {
        CopyFollow made = new CopyFollow(followId, leader.getId(), FOLLOWER, ACCOUNT, CopyMode.FIXED_RATIO, null, 1000);
        Mockito.when(follows.findById(followId)).thenReturn(Optional.of(made));

        assertThat(service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null, 50, 500 * ONE, "ip"))
                .isSameAs(made);
        Mockito.verifyNoInteractions(subAccounts, transfers);
        // Somebody else's id is not theirs to replay.
        assertThatThrownBy(() -> service.follow(followId, 77L, leader.getId(), CopyMode.FIXED_RATIO, null, 50,
                500 * ONE, "ip")).isInstanceOf(FollowNotFoundException.class);
    }

    @Test
    @DisplayName("nobody follows a leader that does not exist, is not active, is full, or is themselves")
    void whoMayBeFollowed() {
        assertThatThrownBy(() -> follow(Ulid.randomUuid(), FOLLOWER)).isInstanceOf(LeaderNotFoundException.class);
        assertThatThrownBy(() -> follow(leader.getId(), LEADER_USER)).isInstanceOf(SelfFollowException.class);

        Mockito.when(follows.countByLeaderIdAndStatus(leader.getId(), FollowStatus.ACTIVE)).thenReturn(300L);
        assertThatThrownBy(() -> follow(leader.getId(), FOLLOWER)).isInstanceOf(LeaderNotAcceptingException.class);

        Mockito.when(follows.countByLeaderIdAndStatus(leader.getId(), FollowStatus.ACTIVE)).thenReturn(0L);
        leader.setStatus(LeaderStatus.SUSPENDED);
        assertThatThrownBy(() -> follow(leader.getId(), FOLLOWER)).isInstanceOf(LeaderNotAcceptingException.class);
        Mockito.verifyNoInteractions(subAccounts, transfers);
    }

    @Test
    @DisplayName("one open copy account per leader, and no more than the limit in all; a closed one counts for neither")
    void howManyCopies() {
        CopyFollow closed = new CopyFollow(Ulid.randomUuid(), leader.getId(), FOLLOWER, 50L, CopyMode.FIXED_RATIO, null, 1000);
        closed.setStatus(FollowStatus.CLOSED);
        Mockito.when(follows.findByUserIdOrderByCreatedAtDesc(FOLLOWER)).thenReturn(List.of(closed));
        follow(leader.getId(), FOLLOWER);

        CopyFollow closing = new CopyFollow(Ulid.randomUuid(), leader.getId(), FOLLOWER, 51L, CopyMode.FIXED_RATIO, null, 1000);
        closing.setStatus(FollowStatus.CLOSING);
        Mockito.when(follows.findByUserIdOrderByCreatedAtDesc(FOLLOWER)).thenReturn(List.of(closing));
        assertThatThrownBy(() -> follow(leader.getId(), FOLLOWER)).isInstanceOf(AlreadyFollowingException.class);

        CopyFollow other = new CopyFollow(Ulid.randomUuid(), Ulid.randomUuid(), FOLLOWER, 52L, CopyMode.FIXED_RATIO, null, 1000);
        Mockito.when(follows.findByUserIdOrderByCreatedAtDesc(FOLLOWER)).thenReturn(Collections.nCopies(10, other));
        assertThatThrownBy(() -> follow(leader.getId(), FOLLOWER)).isInstanceOf(CopyLimitReachedException.class);
    }

    @Test
    @DisplayName("each mode has its minimum, and a fixed amount needs an amount per order the funding can cover")
    void amounts() {
        assertThatThrownBy(() -> service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_RATIO, null, 50,
                99 * ONE, "ip")).isInstanceOf(CopyAmountTooSmallException.class);
        assertThatThrownBy(() -> service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_AMOUNT, null, 50,
                60 * ONE, "ip")).as("no amount per order").isInstanceOf(CopyAmountTooSmallException.class);
        assertThatThrownBy(() -> service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_AMOUNT, 5 * ONE, 50,
                60 * ONE, "ip")).as("an amount per order under the minimum").isInstanceOf(CopyAmountTooSmallException.class);
        assertThatThrownBy(() -> service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_AMOUNT, 80 * ONE, 50,
                60 * ONE, "ip")).as("more per order than was put in").isInstanceOf(CopyAmountTooSmallException.class);

        CopyFollow fixed = service.follow(followId, FOLLOWER, leader.getId(), CopyMode.FIXED_AMOUNT, 20 * ONE, 50,
                60 * ONE, "ip");
        assertThat(fixed.getAmountPerOrder()).isEqualTo(20 * ONE);
    }

    @Test
    @DisplayName("funds go in freely and come out down to the mode's minimum, each under the client's transfer id")
    void funds() {
        active(CopyMode.FIXED_RATIO);
        Balance held = Mockito.mock(Balance.class);
        Mockito.when(held.getAvailable()).thenReturn(300 * ONE);
        Mockito.when(balances.findByUserIdAndAssetId(ACCOUNT, USDT)).thenReturn(Optional.of(held));
        UUID in = Ulid.randomUuid();
        UUID out = Ulid.randomUuid();

        service.addFunds(FOLLOWER, followId, in, 50 * ONE);
        service.removeFunds(FOLLOWER, followId, out, 200 * ONE);

        Mockito.verify(transfers).transfer(in, FOLLOWER, ACCOUNT, USDT, 50 * ONE, FOLLOWER);
        Mockito.verify(transfers).transfer(out, ACCOUNT, FOLLOWER, USDT, 200 * ONE, FOLLOWER);
        // The share of what has been realized is settled before the follower takes out of the rest.
        Mockito.inOrder(shares, transfers).verify(shares).settle(followId, ShareTrigger.WITHDRAWAL, null);
        Mockito.verify(shares, Mockito.never()).settle(eq(followId), eq(ShareTrigger.CLOSE), any());
        assertThatThrownBy(() -> service.removeFunds(FOLLOWER, followId, Ulid.randomUuid(), 201 * ONE))
                .as("300 less 201 is under the 100 a fixed ratio needs")
                .isInstanceOf(CopyBalanceBelowMinimumException.class);
    }

    @Test
    @DisplayName("a copy account that is somebody else's is not found; one that is closing takes no more funds")
    void ownedAndActive() {
        CopyFollow follow = active(CopyMode.FIXED_RATIO);
        assertThatThrownBy(() -> service.addFunds(77L, followId, Ulid.randomUuid(), ONE))
                .isInstanceOf(FollowNotFoundException.class);

        follow.setStatus(FollowStatus.CLOSING);
        assertThatThrownBy(() -> service.addFunds(FOLLOWER, followId, Ulid.randomUuid(), ONE))
                .isInstanceOf(FollowNotActiveException.class);
        assertThatThrownBy(() -> service.removeFunds(FOLLOWER, followId, Ulid.randomUuid(), ONE))
                .isInstanceOf(FollowNotActiveException.class);
        Mockito.verify(transfers, Mockito.never()).transfer(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("stopping starts the closing once; a second stop is told it is already closing")
    void stops() {
        active(CopyMode.FIXED_RATIO);
        Mockito.when(closes.begin(followId, CloseReason.STOPPED)).thenReturn(true, false);

        service.stop(FOLLOWER, followId);

        assertThatThrownBy(() -> service.stop(FOLLOWER, followId)).isInstanceOf(FollowNotActiveException.class);
        assertThatThrownBy(() -> service.stop(77L, followId)).isInstanceOf(FollowNotFoundException.class);
    }

    private CopyFollow follow(UUID leaderId, long userId) {
        return service.follow(Ulid.randomUuid(), userId, leaderId, CopyMode.FIXED_RATIO, null, 50, 500 * ONE, "ip");
    }

    private CopyFollow active(CopyMode mode) {
        CopyFollow follow = new CopyFollow(followId, leader.getId(), FOLLOWER, ACCOUNT, mode, null, 1000);
        Mockito.when(follows.findById(followId)).thenReturn(Optional.of(follow));
        return follow;
    }
}
