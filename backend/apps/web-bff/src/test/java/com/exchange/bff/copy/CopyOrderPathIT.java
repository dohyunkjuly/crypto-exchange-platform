package com.exchange.bff.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leadorder.CopyAccountOrderHook;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SubAccountOrderHook;
import com.exchange.spot.order.exception.ManualOrderNotAllowedException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The order path's hook for lead and copy accounts, wired and against the real tables
 * (docs/reference/copy-trading.md). An app that places orders without it places them
 * unchecked, and nothing else would say so.
 *
 * <p>It calls the hook, not {@code placeOrder}: a placed order is a command the running engine
 * would match, and its row could not be cleaned up from under settlement.
 */
@SpringBootTest(properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class CopyOrderPathIT {

    private static final long ONE = 100_000_000L;

    @Autowired private OrderService orders;
    @Autowired private SubAccountOrderHook hook;
    @Autowired private SubAccountService subAccounts;
    @Autowired private UserRepository users;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyLeadOrderRepository leadOrders;
    @Autowired private BalanceService balances;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    private final List<Long> accounts = new ArrayList<>();
    private final UUID leaderId = Ulid.randomUuid();

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092, 27018}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
    }

    @AfterEach
    void removeWhatWasMade() {
        jdbc.update("DELETE FROM copy_lead_orders WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) Ulid.toBytes(leaderId));
        // Subs before their master: parent_user_id is a real foreign key, same domain.
        for (Long id : accounts.reversed()) {
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id = ?", id);
            jdbc.update("DELETE FROM wallet_balances WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_auth_events WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("the order path holds the copy hook: a sub-account's order cannot get past it unseen")
    void theHookIsWired() {
        assertThat(hook).isInstanceOf(CopyAccountOrderHook.class);
        assertThat(ReflectionTestUtils.getField(orders, "subAccountHook")).isEqualTo(Optional.of(hook));
    }

    @Test
    @DisplayName("a lead account's buy leaves its share of the available USDT behind; a copy account's is refused")
    void recordsAndRefuses() {
        SpotSymbol btcUsdt = markets.findBySymbol("BTCUSDT").orElseThrow();
        Long master = account(users.save(new User("copy-path-" + suffix + "@x.dev", null)));
        Long lead = account(subAccounts.createForCopyTrading(master, SubPurpose.LEAD, "lead", "127.0.0.1"));
        Long copy = account(subAccounts.createForCopyTrading(master, SubPurpose.COPY, "copy", "127.0.0.1"));
        CopyLeader leader = new CopyLeader(leaderId, master, "lead-" + suffix, null);
        leader.setLeadUserId(lead);
        leader.setStatus(LeaderStatus.ACTIVE);
        leaders.save(leader);
        balances.credit(lead, btcUsdt.getQuoteAssetId(), 1_000 * ONE, ReferenceType.DEPOSIT, "copy-path-it",
                "copy-path-it:" + suffix);

        UUID orderId = Ulid.randomUuid();
        hook.beforeLock(orderId, lead, btcUsdt, Side.BUY, 250 * ONE, ONE / 100);

        CopyLeadOrder recorded = leadOrders.findById(orderId).orElseThrow();
        assertThat(recorded.getFraction()).isEqualTo(CopyLeadOrder.WHOLE / 4);
        assertThat(recorded.getLeaderId()).isEqualTo(leaderId);
        assertThat(recorded.getCopiedAt()).isNull();

        assertThatThrownBy(() -> hook.beforeLock(Ulid.randomUuid(), copy, btcUsdt, Side.BUY, 10 * ONE, ONE / 1000))
                .isInstanceOf(ManualOrderNotAllowedException.class);
    }

    private Long account(User user) {
        accounts.add(user.getId());
        return user.getId();
    }
}
