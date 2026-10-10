package com.exchange.wallet.balance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Settlement;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * When the announcement is sent, relative to the commit -- which is the whole reason this
 * class exists rather than a {@code kafka.send()} inside {@link BalanceService}.
 *
 * <p>No Spring context and no database. {@code TransactionSynchronizationManager} can be
 * driven by hand, so "the transaction committed" and "the transaction rolled back" are both
 * expressible as a plain method call -- which makes the property testable in milliseconds
 * instead of needing a real transaction to fail inside a real context.
 */
@ExtendWith(MockitoExtension.class)
class BalanceChangePublisherTest {

    private static final Long USER_ID = 7L;
    private static final Long ASSET_ID = 2L;

    @Mock
    private KafkaTemplate<String, byte[]> kafka;

    private BalanceChangePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new BalanceChangePublisher(kafka);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        publisher.shutdown();
    }

    /**
     * The property everything else depends on. A balance saved is not a balance committed --
     * {@code BalanceService}'s callers nest, so an inner write joins an outer transaction that
     * may still roll back. Announcing here would put a balance on the wire that never existed,
     * and a client would render it and stay wrong until that account's next change.
     */
    @Test
    void nothingIsSentBeforeTheTransactionCommits() {
        publisher.publishAfterCommit(balance(1_000L, 0L, 4L));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    /** And a rollback sends nothing at all, ever -- the synchronization simply never reaches
     *  its commit phase. */
    @Test
    void arollbackSendsNothing() {
        publisher.publishAfterCommit(balance(1_000L, 0L, 4L));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void thebalanceIsSentOnceTheTransactionCommits() throws Exception {
        publisher.publishAfterCommit(balance(600L, 400L, 4L));

        commit();

        Settlement sent = captureSent();
        assertSettlement(sent, 600L, 400L, 4L);
    }

    /**
     * The subtlety that decides where the values are read. Hibernate increments
     * {@code @Version} when it flushes the UPDATE, which happens <em>after</em>
     * {@code publishAfterCommit} is called and before the commit -- so capturing the version
     * at registration time would publish the number the row had before the write. Every
     * consumer discards anything not newer than what it holds, so an off-by-one version is
     * not a cosmetic error: the change would be dropped as stale.
     */
    @Test
    void valuesAreReadAtCommitTimeNotAtRegistration() throws Exception {
        Balance balance = balance(1_000L, 0L, 4L);
        publisher.publishAfterCommit(balance);

        // What the flush does between registration and commit.
        ReflectionTestUtils.setField(balance, "version", 5L);
        balance.lock(250L);

        commit();

        Settlement sent = captureSent();
        assertSettlement(sent, 750L, 250L, 5L);
    }

    /** Keyed by user id so everything about one account shares a partition -- which is what
     *  keeps a fill and the balance change it caused in the order they happened. */
    @Test
    void messagesAreKeyedByUserId() {
        publisher.publishAfterCommit(balance(1_000L, 0L, 4L));

        commit();

        verify(kafka, timeout(2_000))
                .send(eq(Topics.SPOT_SETTLEMENTS), eq(USER_ID.toString()), any());
    }

    /**
     * A broker failure is a dropped notification, not a failed balance change. The balance is
     * already committed and readable over REST, so throwing here would turn a cosmetic
     * problem into a visible one -- and by this point there is no transaction left to fail.
     */
    @Test
    void asendFailureIsSwallowed() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("broker is down"));

        publisher.publishAfterCommit(balance(1_000L, 0L, 4L));

        assertThatCode(this::commit).doesNotThrowAnyException();
        // Waits for the send to actually have been attempted. Without this the test is a
        // race: the throw happens on the executor thread, so if the test method returns
        // first, Mockito's strict stubbing reports the stub as unused and fails -- passing
        // or failing depending on thread scheduling, which is the worst of both.
        verify(kafka, timeout(2_000)).send(anyString(), anyString(), any());
    }

    /** Called outside a transaction there is no commit to wait for, so there is nothing
     *  truthful to announce -- and guessing would be the phantom-event bug by another route. */
    @Test
    void withoutATransactionNothingIsRegisteredOrSent() {
        TransactionSynchronizationManager.clearSynchronization();

        publisher.publishAfterCommit(balance(1_000L, 0L, 4L));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    // --- helpers ---

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private Settlement captureSent() throws InvalidProtocolBufferException {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        // timeout, not immediate: the send is handed to an executor on purpose, so the
        // calling thread is gone by the time it happens.
        verify(kafka, timeout(2_000)).send(eq(Topics.SPOT_SETTLEMENTS), anyString(),
                payload.capture());
        return Settlement.parseFrom(payload.getValue());
    }

    private static void assertSettlement(Settlement sent, long free, long locked, long version) {
        org.assertj.core.api.Assertions.assertThat(sent.getUserId()).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(sent.hasBalanceChanged()).isTrue();
        org.assertj.core.api.Assertions.assertThat(sent.getBalanceChanged().getAssetId())
                .isEqualTo(ASSET_ID);
        org.assertj.core.api.Assertions.assertThat(sent.getBalanceChanged().getFree())
                .isEqualTo(free);
        org.assertj.core.api.Assertions.assertThat(sent.getBalanceChanged().getLocked())
                .isEqualTo(locked);
        org.assertj.core.api.Assertions.assertThat(sent.getBalanceChanged().getVersion())
                .isEqualTo(version);
    }

    private static Balance balance(long available, long locked, long version) {
        Balance balance = new Balance(USER_ID, ASSET_ID);
        balance.credit(available + locked);
        if (locked > 0) {
            balance.lock(locked);
        }
        ReflectionTestUtils.setField(balance, "version", version);
        return balance;
    }
}
