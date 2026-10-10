package com.exchange.bff.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.copy.stats.CopyLeaderSnapshot;
import com.exchange.copy.stats.CopyLeaderSnapshotRepository;
import com.exchange.copy.stats.CopyLeaderStats;
import com.exchange.copy.stats.CopyLeaderStatsRepository;
import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.subaccount.SubAccountService;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Following a leader from the web, over real HTTP and the real tables
 * (docs/reference/copy-trading.md): the leaders are public, following is one request that makes
 * the copy account and funds it, and only its owner may fund, read or stop it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class CopyFollowIT {

    private static final long ONE = 100_000_000L;
    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private SubAccountService subAccounts;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyFollowRepository follows;
    @Autowired private CopyLeaderService leaderService;
    @Autowired private CopyLeaderStatsRepository stats;
    @Autowired private CopyLeaderSnapshotRepository snapshots;
    @Autowired private BalanceService balanceService;
    @Autowired private BalanceRepository balances;
    @Autowired private AssetRepository assets;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    private final List<Long> accounts = new ArrayList<>();
    private final UUID leaderId = Ulid.randomUuid();
    private Long usdt;

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

    @BeforeEach
    void aLeaderToFollow() {
        usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long master = users.save(new User("copy-lead-" + suffix + "@x.dev", null)).getId();
        accounts.add(master);
        Long lead = subAccounts.createForCopyTrading(master, SubPurpose.LEAD, "lead", "127.0.0.1").getId();
        accounts.add(lead);
        CopyLeader leader = new CopyLeader(leaderId, master, "Mina " + suffix, "trades the majors");
        leader.setLeadUserId(lead);
        leader.setStatus(LeaderStatus.ACTIVE);
        leaders.save(leader);
    }

    @AfterEach
    void removeWhatWasMade() {
        for (CopyFollow follow : follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)) {
            accounts.add(follow.getCopyUserId());
        }
        jdbc.query("SELECT copy_user_id FROM copy_follows WHERE leader_id = ?",
                rs -> { accounts.add(rs.getLong(1)); }, (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE o FROM copy_orders o JOIN copy_follows f ON f.id = o.follow_id WHERE f.leader_id = ?",
                (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_follows WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_leader_stats WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_leader_snapshots WHERE leader_id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) Ulid.toBytes(leaderId));
        // An application a test made over HTTP, whatever became of it.
        for (Long id : accounts) {
            jdbc.update("DELETE FROM copy_leaders WHERE user_id = ?", id);
        }
        new SeededDataCleaner(jdbc).purgeUsers(accounts.stream().distinct().toList());
    }

    @Test
    @DisplayName("anyone can see who may be followed and on what terms, without a session")
    void leadersArePublic() {
        JsonNode body = get(null, "/bff/copy/leaders").getBody().get("data");

        JsonNode mine = find(body.get("leaders"), leaderId);
        assertThat(mine.get("displayName").asText()).isEqualTo("Mina " + suffix);
        assertThat(mine.get("followers").asLong()).isZero();
        assertThat(mine.get("accepting").asBoolean()).isTrue();
        assertThat(mine.toString()).as("never the leader's address or customer id").doesNotContain("@")
                .doesNotContain("userId");
        assertThat(body.get("terms").get("quoteAsset").asText()).isEqualTo("USDT");
        assertThat(body.get("terms").get("minFixedRatioAmount").asText()).isEqualTo("100");
        assertThat(body.get("terms").get("profitShareBps").asInt()).isEqualTo(1000);

        assertThat(get(null, "/bff/copy/leaders/" + leaderId).getBody().get("data").get("leader").get("id").asText())
                .isEqualTo(leaderId.toString());
        assertThat(get(null, "/bff/copy/leaders/" + UUID.randomUUID()).getBody().get("code").asInt()).isEqualTo(8001);
        assertThat(get(null, "/bff/copy/follows").getStatusCode().value()).as("a copy account is not public").isEqualTo(401);
    }

    @Test
    @DisplayName("a leader's figures are shown as the nightly job left them: percentages, and nothing where "
            + "nothing has been measured yet")
    void figuresAreTheNightlyJobs() {
        assertThat(find(get(null, "/bff/copy/leaders").getBody().get("data").get("leaders"), leaderId).get("stats").isNull())
                .as("before the first nightly run").isTrue();

        CopyLeaderStats figures = new CopyLeaderStats(leaderId);
        figures.record(new CopyLeaderStats.Window(1234, 300 * ONE, 120), new CopyLeaderStats.Window(-350, 4_200 * ONE, 850),
                new CopyLeaderStats.Window(null, null, null), 2, 700 * ONE, 7500, 4, Instant.parse("2026-10-02T00:30:00Z"));
        stats.save(figures);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        snapshots.save(new CopyLeaderSnapshot(leaderId, today.minusDays(2), 1_000 * ONE, 0, Instant.now()));
        snapshots.save(new CopyLeaderSnapshot(leaderId, today.minusDays(1), 1_600 * ONE, 500 * ONE, Instant.now()));

        JsonNode listed = find(get(null, "/bff/copy/leaders").getBody().get("data").get("leaders"), leaderId).get("stats");
        assertThat(listed.get("days7").get("return").asText()).isEqualTo("12.34");
        assertThat(listed.get("days7").get("pnl").asText()).isEqualTo("300");
        assertThat(listed.get("days30").get("return").asText()).isEqualTo("-3.5");
        assertThat(listed.get("days30").get("pnl").asText()).isEqualTo("4200");
        assertThat(listed.get("days30").get("maxDrawdown").asText()).isEqualTo("8.5");
        assertThat(listed.get("days90").get("return").isNull()).isTrue();
        assertThat(listed.get("series")).extracting(p -> p.get("value").asText())
                .as("the line: flat, then a tenth up with the 500 added taken off").containsExactly("0", "10");
        assertThat(listed.get("series").get(1).get("day").asText()).isEqualTo(today.minusDays(1).toString());
        assertThat(get(null, "/bff/copy/leaders/" + leaderId).getBody().get("data").get("leadingBalance").asText())
                .as("the lead account as the last snapshot valued it").isEqualTo("1600");
        assertThat(listed.get("copySuccessRate").asText()).isEqualTo("75");
        assertThat(listed.get("fundsCopying").asText()).isEqualTo("700");
        assertThat(get(null, "/bff/copy/leaders/" + leaderId).getBody().get("data").get("leader").get("stats"))
                .isEqualTo(listed);
    }

    @Test
    @DisplayName("following makes and funds the copy account in one request, once; its owner funds it, reads it and stops it")
    void followFundAndStop() {
        HttpHeaders session = followerWith(1_000 * ONE);
        long follower = userId(session);
        UUID followId = Ulid.randomUuid();
        Map<String, Object> request = Map.of("followId", followId.toString(), "leaderId", leaderId.toString(),
                "mode", "FIXED_RATIO", "slippageCapBps", 50, "amount", "500");

        ResponseEntity<JsonNode> made = post(session, "/bff/copy/follows", request);
        assertThat(made.getStatusCode().value()).as(String.valueOf(made.getBody())).isEqualTo(201);
        assertThat(made.getBody().get("data").get("leaderName").asText()).isEqualTo("Mina " + suffix);
        assertThat(made.getBody().get("data").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(made.getBody().get("data").get("slippageCapBps").asInt()).isEqualTo(50);

        // The same request again: the same follow, and the money moved once.
        assertThat(post(session, "/bff/copy/follows", request).getBody().get("data").get("id").asText())
                .isEqualTo(followId.toString());
        long copyAccount = follows.findById(followId).orElseThrow().getCopyUserId();
        assertThat(available(follower)).isEqualTo(500 * ONE);
        assertThat(available(copyAccount)).isEqualTo(500 * ONE);
        assertThat(users.findById(copyAccount).orElseThrow().getSubPurpose()).isEqualTo(SubPurpose.COPY);

        // One copy account per leader.
        assertThat(post(session, "/bff/copy/follows", Map.of("followId", UUID.randomUUID().toString(),
                "leaderId", leaderId.toString(), "mode", "FIXED_RATIO", "slippageCapBps", 50, "amount", "200")).getBody().get("code").asInt())
                .isEqualTo(8003);

        // In freely; out only down to the 100 a fixed ratio needs.
        assertThat(post(session, "/bff/copy/follows/" + followId + "/deposits",
                Map.of("transferId", UUID.randomUUID().toString(), "amount", "100")).getStatusCode().value()).isEqualTo(204);
        assertThat(post(session, "/bff/copy/follows/" + followId + "/withdrawals",
                Map.of("transferId", UUID.randomUUID().toString(), "amount", "550")).getBody().get("code").asInt())
                .isEqualTo(8009);
        assertThat(post(session, "/bff/copy/follows/" + followId + "/withdrawals",
                Map.of("transferId", UUID.randomUUID().toString(), "amount", "200")).getStatusCode().value()).isEqualTo(204);
        assertThat(available(copyAccount)).isEqualTo(400 * ONE);
        assertThat(available(follower)).isEqualTo(600 * ONE);

        JsonNode listed = get(session, "/bff/copy/follows").getBody().get("data");
        assertThat(listed).hasSize(1);
        JsonNode holding = listed.get(0).get("holdings").get(0);
        assertThat(holding.get("asset").asText()).isEqualTo("USDT");
        assertThat(holding.get("available").asText()).isEqualTo("400");
        assertThat(listed.get(0).get("value").asText()).as("USDT only so far").isEqualTo("400");
        assertThat(get(session, "/bff/copy/follows/" + followId + "/shares?page=0").getBody().get("data")).isEmpty();
        assertThat(get(session, "/bff/copy/follows/" + followId + "/copies?page=0").getBody().get("data")).isEmpty();
        assertThat(get(null, "/bff/copy/leaders/" + leaderId).getBody().get("data").get("leader").get("followers").asLong())
                .isEqualTo(1);

        // Somebody else cannot see it, fund it or stop it.
        HttpHeaders stranger = followerWith(1_000 * ONE);
        assertThat(get(stranger, "/bff/copy/follows/" + followId).getBody().get("code").asInt()).isEqualTo(8007);
        assertThat(get(stranger, "/bff/copy/follows/" + followId + "/copies").getBody().get("code").asInt()).isEqualTo(8007);
        assertThat(get(stranger, "/bff/copy/follows/" + followId + "/shares").getBody().get("code").asInt()).isEqualTo(8007);
        assertThat(post(stranger, "/bff/copy/follows/" + followId + "/stop", Map.of()).getBody().get("code").asInt())
                .isEqualTo(8007);

        assertThat(post(session, "/bff/copy/follows/" + followId + "/stop", Map.of()).getStatusCode().value()).isEqualTo(204);
        assertThat(follows.findById(followId).orElseThrow().getStatus()).as("closing, or already closed by the copier")
                .isNotEqualTo(FollowStatus.ACTIVE);
        assertThat(post(session, "/bff/copy/follows/" + followId + "/stop", Map.of()).getBody().get("code").asInt())
                .isEqualTo(8008);
        assertThat(post(session, "/bff/copy/follows/" + followId + "/deposits",
                Map.of("transferId", UUID.randomUUID().toString(), "amount", "50")).getBody().get("code").asInt())
                .isEqualTo(8008);
    }

    @Test
    @DisplayName("following is trading: an account not cleared to trade is refused, and so is too little or too much")
    void whoMayFollowAndWithWhat() {
        HttpHeaders unverified = registerAndLogin();
        Map<String, Object> request = Map.of("followId", UUID.randomUUID().toString(), "leaderId", leaderId.toString(),
                "mode", "FIXED_RATIO", "slippageCapBps", 50, "amount", "500");
        ResponseEntity<JsonNode> gated = post(unverified, "/bff/copy/follows", request);
        assertThat(gated.getStatusCode().value()).isEqualTo(403);
        assertThat(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE)).isEmpty();

        HttpHeaders session = followerWith(300 * ONE);
        assertThat(post(session, "/bff/copy/follows", Map.of("followId", UUID.randomUUID().toString(),
                "leaderId", leaderId.toString(), "mode", "FIXED_RATIO", "slippageCapBps", 50, "amount", "99")).getBody().get("code").asInt())
                .as("under the minimum").isEqualTo(8005);
        assertThat(post(session, "/bff/copy/follows", Map.of("followId", UUID.randomUUID().toString(),
                "leaderId", leaderId.toString(), "mode", "FIXED_AMOUNT", "slippageCapBps", 50, "amount", "50")).getBody().get("code").asInt())
                .as("a fixed amount with no amount per order").isEqualTo(8005);
        assertThat(post(session, "/bff/copy/follows", request).getBody().get("code").asInt())
                .as("more than the account holds").isEqualTo(4001);
        assertThat(follows.findByLeaderIdAndStatus(leaderId, FollowStatus.ACTIVE))
                .as("a refused follow leaves no account and no follow behind").isEmpty();
        assertThat(available(userId(session))).isEqualTo(300 * ONE);
    }

    @Test
    @DisplayName("a customer becomes a leader in one funded step, keeps the lead account's minimum while leading, "
            + "and is never told that staff stopped them or why")
    void leading() {
        HttpHeaders session = followerWith(2_000 * ONE);
        long me = userId(session);
        String name = "Joon " + suffix;
        Map<String, Object> application = Map.of("displayName", name, "bio", "swing", "showPortfolio", true,
                "transferId", UUID.randomUUID().toString(), "amount", "1000");

        assertThat(get(session, "/bff/copy/lead").getBody().get("data").get("lead").isNull()).isTrue();
        assertThat(post(session, "/bff/copy/lead/apply", application).getBody().get("code").asInt())
                .as("the bar is the API key's: BASIC is not verified far enough to lead").isEqualTo(2007);
        User user = users.findById(me).orElseThrow();
        user.setKycLevel(KycLevel.INTERMEDIATE);
        users.save(user);
        assertThat(post(session, "/bff/copy/lead/apply", new HashMap<>(application) {{ put("amount", "100"); }})
                .getBody().get("code").asInt()).as("under the 500 a lead account keeps").isEqualTo(8017);

        // One step: the lead account exists, is funded, and leads -- nobody waited for staff.
        ResponseEntity<JsonNode> applied = post(session, "/bff/copy/lead/apply", application);
        assertThat(applied.getStatusCode().value()).as(String.valueOf(applied.getBody())).isEqualTo(201);
        assertThat(applied.getBody().get("data").get("status").asText()).isEqualTo("ACTIVE");
        UUID mine = UUID.fromString(applied.getBody().get("data").get("id").asText());
        Long leadAccount = leaderService.mine(me).orElseThrow().leader().getLeadUserId();
        accounts.add(leadAccount);
        assertThat(available(leadAccount)).isEqualTo(1_000 * ONE);
        assertThat(available(me)).isEqualTo(1_000 * ONE);
        assertThat(post(session, "/bff/copy/lead/apply", application).getBody().get("code").asInt()).isEqualTo(8010);
        HttpHeaders stranger = followerWith(ONE);
        User other = users.findById(userId(stranger)).orElseThrow();
        other.setKycLevel(KycLevel.INTERMEDIATE);
        users.save(other);
        assertThat(post(stranger, "/bff/copy/lead/apply", application).getBody().get("code").asInt())
                .as("somebody else's name, refused before any money moves").isEqualTo(8011);
        assertThat(available(userId(stranger))).isEqualTo(ONE);

        assertThat(post(session, "/bff/copy/lead/withdrawals", Map.of("transferId", UUID.randomUUID().toString(),
                "asset", "USDT", "amount", "600")).getBody().get("code").asInt()).as("under the 500 it keeps").isEqualTo(8013);
        assertThat(post(session, "/bff/copy/lead/withdrawals", Map.of("transferId", UUID.randomUUID().toString(),
                "asset", "USDT", "amount", "500")).getStatusCode().value()).isEqualTo(204);

        JsonNode page = get(session, "/bff/copy/lead").getBody().get("data");
        assertThat(page.get("lead").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(page.get("lead").get("holdings").get(0).get("available").asText()).isEqualTo("500");
        assertThat(page.get("minBalance").asText()).isEqualTo("500");
        assertThat(get(null, "/bff/copy/leaders").getBody().get("data").get("leaders").toString()).contains(name);
        assertThat(get(session, "/bff/sub-accounts").getBody().get("data")).as("a lead account is not an ordinary sub").isEmpty();

        // The profile shows what the lead account holds, until the leader keeps it private.
        JsonNode shown = get(null, "/bff/copy/leaders/" + mine).getBody().get("data");
        assertThat(shown.get("leader").get("portfolioShown").asBoolean()).isTrue();
        assertThat(shown.get("portfolio").get("holdings").get(0).get("asset").asText()).isEqualTo("USDT");
        assertThat(shown.get("portfolio").get("holdings").get(0).get("amount").asText()).isEqualTo("500");
        assertThat(shown.get("portfolio").has("trades")).as("paged on their own").isFalse();
        JsonNode traded = get(null, "/bff/copy/leaders/" + mine + "/trades?page=0").getBody().get("data");
        assertThat(traded.isArray()).isTrue();
        assertThat(traded).isEmpty();
        assertThat(patch(session, "/bff/copy/lead/portfolio", Map.of("shown", false)).getBody().get("data")
                .get("portfolioShown").asBoolean()).isFalse();
        JsonNode hidden = get(null, "/bff/copy/leaders/" + mine).getBody().get("data");
        assertThat(hidden.get("portfolio").isNull()).as("the positions are the leader's to show").isTrue();
        assertThat(get(null, "/bff/copy/leaders/" + mine + "/trades").getBody().get("data")).as("and so are the trades").isEmpty();
        assertThat(hidden.get("leader").get("stats").isNull()).as("the figures are not").isTrue();

        // Staff suspend: the customer sees that it is unavailable, and nothing about why.
        leaderService.suspend(1L, mine, "wash trading with a second account");
        JsonNode stopped = get(session, "/bff/copy/lead").getBody().get("data").get("lead");
        assertThat(stopped.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(stopped.toString()).doesNotContain("wash").doesNotContain("SUSPENDED").doesNotContain("reason");
        assertThat(get(null, "/bff/copy/leaders").getBody().get("data").get("leaders").toString()).doesNotContain(name);
        // No longer leading, so everything can come out.
        assertThat(post(session, "/bff/copy/lead/withdrawals", Map.of("transferId", UUID.randomUUID().toString(),
                "asset", "USDT", "amount", "500")).getStatusCode().value()).isEqualTo(204);
        assertThat(available(me)).isEqualTo(2_000 * ONE);
        assertThat(post(session, "/bff/copy/lead/close", Map.of()).getBody().get("code").asInt())
                .as("only an active leader stops leading").isEqualTo(8014);
    }

    @Test
    @DisplayName("a follower whose leader was stopped by staff is told the leader stopped, not that staff stopped them")
    void aSuspendedLeaderIsNotNamedToFollowers() {
        HttpHeaders session = followerWith(1_000 * ONE);
        UUID followId = Ulid.randomUuid();
        post(session, "/bff/copy/follows", Map.of("followId", followId.toString(), "leaderId", leaderId.toString(),
                "mode", "FIXED_RATIO", "slippageCapBps", 50, "amount", "500"));
        jdbc.update("UPDATE copy_follows SET status = 'CLOSED', close_reason = 'LEADER_SUSPENDED' WHERE id = ?",
                (Object) Ulid.toBytes(followId));

        JsonNode shown = get(session, "/bff/copy/follows/" + followId).getBody().get("data").get("follow");
        assertThat(shown.get("closeReason").asText()).isEqualTo("LEADER_CLOSED");
        assertThat(get(session, "/bff/copy/follows").getBody().toString()).doesNotContain("SUSPENDED");
    }

    /** A customer cleared to trade, holding that much USDT. */
    private HttpHeaders followerWith(long funded) {
        HttpHeaders session = registerAndLogin();
        long id = userId(session);
        User user = users.findById(id).orElseThrow();
        user.setEmailVerified(true);
        user.setKycLevel(KycLevel.BASIC);
        user.setMfaEnabled(true);
        users.save(user);
        balanceService.credit(id, usdt, funded, ReferenceType.DEPOSIT, "copy-follow-it", "copy-follow-it:" + id);
        return session;
    }

    private HttpHeaders registerAndLogin() {
        String email = "copy-follow-" + UUID.randomUUID() + "@x.dev";
        post(null, "/bff/auth/register", Map.of("email", email, "password", PASSWORD, "name", "IT"));
        ResponseEntity<JsonNode> loggedIn = post(null, "/bff/auth/login", Map.of("email", email, "password", PASSWORD));
        HttpHeaders session = new HttpHeaders();
        session.add(HttpHeaders.COOKIE, loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0]);
        accounts.add(userId(session));
        return session;
    }

    private long userId(HttpHeaders session) {
        return get(session, "/bff/auth/me").getBody().get("data").get("id").asLong();
    }

    private long available(long userId) {
        return balances.findByUserIdAndAssetId(userId, usdt).map(Balance::getAvailable).orElse(0L);
    }

    private static JsonNode find(JsonNode leaders, UUID id) {
        for (JsonNode leader : leaders) {
            if (leader.get("id").asText().equals(id.toString())) {
                return leader;
            }
        }
        throw new AssertionError("leader " + id + " is not listed");
    }

    private ResponseEntity<JsonNode> get(HttpHeaders session, String path) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(session == null ? new HttpHeaders() : session), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(HttpHeaders session, String path, Object body) {
        // HttpURLConnection, TestRestTemplate's default, cannot send PATCH.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.addAll(session);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.PATCH, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(HttpHeaders session, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.addAll(session);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }
}
