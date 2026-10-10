package com.exchange.bff.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.common.id.Ulid;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.position.CopyPosition;
import com.exchange.copy.position.CopyPositionRepository;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.spot.order.Side;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V17__copy_trading.sql against its entities (docs/reference/copy-trading.md): each table
 * takes and gives back its row, and the three things the schema itself must refuse are refused
 * -- they are what the copier leans on without checking.
 */
@SpringBootTest(properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class CopySchemaIT {

    private static final long ONE = 100_000_000L;

    @Autowired private CopySettingsRepository settings;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyFollowRepository follows;
    @Autowired private CopyLeadOrderRepository leadOrders;
    @Autowired private CopyOrderRepository copyOrders;
    @Autowired private CopyPositionRepository positions;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    // Ids no real row has: nothing here is a foreign key, so they need not exist anywhere.
    private final long master = System.nanoTime();
    private final long copyAccount = master + 1;
    private final long market = master + 2;
    private final UUID leaderId = Ulid.randomUuid();
    private final UUID followId = Ulid.randomUuid();
    private final UUID leadOrderId = Ulid.randomUuid();
    private final String suffix = Long.toString(master % 1_000_000_000L);

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
        jdbc.update("DELETE FROM copy_positions WHERE follow_id = ?", (Object) Ulid.toBytes(followId));
        jdbc.update("DELETE FROM copy_orders WHERE follow_id = ?", (Object) Ulid.toBytes(followId));
        jdbc.update("DELETE FROM copy_lead_orders WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_follows WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM user_users WHERE email LIKE ?", "copy-schema-%-" + suffix + "@x.dev");
    }

    @Test
    @DisplayName("the migration leaves one settings row with what staff start from")
    void settingsRow() {
        CopySettings row = settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
        assertThat(row.getFailureLimit()).isEqualTo(5);
        assertThat(row.getProfitShareBps()).isEqualTo(1000);
        assertThat(row.getLeadMinBalance()).isEqualTo(500 * ONE);
        assertThat(row.getMinFixedRatioAmount()).isEqualTo(100 * ONE);
        assertThat(row.getMinFixedAmount()).isEqualTo(10 * ONE);
    }

    @Test
    @DisplayName("every table takes its row and gives it back")
    void roundTrips() {
        leaders.save(new CopyLeader(leaderId, master, "lead-" + suffix, "trades the majors"));
        CopyLeader leader = leaders.findByUserId(master).orElseThrow();
        assertThat(leader.getStatus()).isEqualTo(LeaderStatus.ACTIVE);
        assertThat(leader.getLeadUserId()).isNull();

        follows.save(new CopyFollow(followId, leaderId, master, copyAccount, CopyMode.FIXED_AMOUNT, 20 * ONE, 1000));
        CopyFollow follow = follows.findByCopyUserId(copyAccount).orElseThrow();
        assertThat(follow.getAmountPerOrder()).isEqualTo(20 * ONE);
        assertThat(follow.getSlippageCapBps()).isEqualTo(CopyFollow.DEFAULT_SLIPPAGE_CAP_BPS);
        assertThat(follow.getFailuresInRow()).isZero();
        assertThat(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)).containsExactly(follow);

        leadOrders.save(new CopyLeadOrder(leadOrderId, leaderId, market, Side.BUY, CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE));
        assertThat(leadOrders.findById(leadOrderId).orElseThrow().getFraction()).isEqualTo(CopyLeadOrder.WHOLE / 2);

        UUID copied = Ulid.randomUuid();
        copyOrders.save(new CopyOrder(Ulid.randomUuid(), followId, leadOrderId, copied, market, Side.BUY,
                CopyOrderStatus.PLACED, null));
        assertThat(copyOrders.findByOrderId(copied).orElseThrow().getStatus()).isEqualTo(CopyOrderStatus.PLACED);
        assertThat(copyOrders.existsByLeadOrderIdAndFollowId(leadOrderId, followId)).isTrue();

        CopyPosition position = new CopyPosition(followId, market);
        position.setQuantity(ONE);
        position.setCost(60_000 * ONE);
        positions.save(position);
        assertThat(positions.findByFollowIdAndAssetId(followId, market).orElseThrow().getCost()).isEqualTo(60_000 * ONE);

    }

    @Test
    @DisplayName("one lead order is copied once per follow; closing sales, which copy no lead order, may be many")
    void aCopyHappensOnce() {
        copyOrders.save(new CopyOrder(Ulid.randomUuid(), followId, leadOrderId, Ulid.randomUuid(), market, Side.BUY,
                CopyOrderStatus.PLACED, null));
        assertThatThrownBy(() -> copyOrders.save(new CopyOrder(Ulid.randomUuid(), followId, leadOrderId,
                Ulid.randomUuid(), market, Side.BUY, CopyOrderStatus.PLACED, null)))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (int i = 0; i < 2; i++) {
            copyOrders.save(new CopyOrder(Ulid.randomUuid(), followId, null, Ulid.randomUuid(), market, Side.SELL,
                    CopyOrderStatus.PLACED, null));
        }
    }

    @Test
    @DisplayName("a fixed amount needs its amount, and a fixed ratio must not carry one")
    void modeAndAmountAgree() {
        assertThatThrownBy(() -> follows.save(new CopyFollow(followId, leaderId, master, copyAccount,
                CopyMode.FIXED_AMOUNT, null, 1000))).hasMessageContaining("ck_copy_follows_amount_per_order");
        assertThatThrownBy(() -> follows.save(new CopyFollow(followId, leaderId, master, copyAccount,
                CopyMode.FIXED_RATIO, 20 * ONE, 1000))).hasMessageContaining("ck_copy_follows_amount_per_order");
    }

    @Test
    @DisplayName("only a sub-account can be a lead or a copy account")
    void purposeIsASubs() {
        User primary = new User("copy-schema-master-" + suffix + "@x.dev", null);
        primary.setSubPurpose(SubPurpose.COPY);
        assertThatThrownBy(() -> users.save(primary)).hasMessageContaining("ck_user_users_sub_purpose_is_a_subs");

        User parent = users.save(new User("copy-schema-parent-" + suffix + "@x.dev", null));
        assertThat(parent.getSubPurpose()).isEqualTo(SubPurpose.STANDARD);
        User sub = new User("copy-schema-sub-" + suffix + "@x.dev", null);
        sub.setType(UserType.SUB);
        sub.setParentUserId(parent.getId());
        sub.setSubPurpose(SubPurpose.LEAD);
        Long subId = users.save(sub).getId();
        assertThat(users.findById(subId).orElseThrow().getSubPurpose()).isEqualTo(SubPurpose.LEAD);
        // The sub first: parent_user_id is a real foreign key, same domain.
        jdbc.update("DELETE FROM user_users WHERE id = ?", subId);
    }
}
