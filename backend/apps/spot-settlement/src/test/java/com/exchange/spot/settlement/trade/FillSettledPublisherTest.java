package com.exchange.spot.settlement.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Settlement;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.spot.trade.SpotTrade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What goes on the wire for a settled fill, and when.
 *
 * <p>The assertion that matters most is what is <em>absent</em>. A trade row holds both
 * parties' ids, both orders and both fees, and a single careless message would hand one user
 * the other's position -- a breach that looks like nothing from the inside, since the frames
 * are well formed and arrive on time.
 */
@ExtendWith(MockitoExtension.class)
class FillSettledPublisherTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final Long BUYER = 11L;
    private static final Long SELLER = 22L;
    private static final Long BASE_ASSET = 1L;
    private static final Long QUOTE_ASSET = 2L;
    private static final long ONE = 100_000_000L;

    @Mock
    private KafkaTemplate<String, byte[]> kafka;

    private FillSettledPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new FillSettledPublisher(kafka);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        publisher.shutdown();
    }

    /** Same commit boundary as every other announcement: a trade saved is not a trade
     *  committed, and the transaction it is in can still roll back. */
    @Test
    void nothingIsSentBeforeTheTransactionCommits() {
        publisher.publishAfterCommit(SYMBOL, List.of(trade(true, 700L, -200L)));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void arollbackSendsNothing() {
        publisher.publishAfterCommit(SYMBOL, List.of(trade(true, 700L, -200L)));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    /** One fill, two people, two messages -- each keyed to its own recipient so it lands in
     *  the partition holding everything else about that account. */
    @Test
    void onefillProducesOneMessagePerParticipant() {
        publisher.publishAfterCommit(SYMBOL, List.of(trade(true, 700L, -200L)));

        commit();

        verify(kafka, timeout(2_000)).send(eq(Topics.SPOT_SETTLEMENTS), eq("11"), any());
        verify(kafka, timeout(2_000)).send(eq(Topics.SPOT_SETTLEMENTS), eq("22"), any());
    }

    /**
     * The breach test. Each message describes one side and the other appears nowhere -- not
     * their user id, not their order id, not their fee.
     */
    @Test
    void neitherSideLearnsAnythingAboutTheOther() throws Exception {
        SpotTrade trade = trade(true, 700L, -200L);
        publisher.publishAfterCommit(SYMBOL, List.of(trade));

        commit();

        Settlement buyers = sentTo("11");
        Settlement sellers = sentTo("22");

        // Asserted field by field rather than by scanning the rendered message for the other
        // user's id: ids are small numbers and match digits anywhere, so a scan for "22" hits
        // the timestamp. These are the assertions that actually catch the bug -- reusing one
        // message for both sides would put the buyer's order and fee in the seller's copy.
        assertThat(buyers.getUserId()).isEqualTo(BUYER);
        assertThat(buyers.getFillSettled().getOrderId()).isEqualTo(bytes(trade.getBuyerOrderId()));
        assertThat(buyers.getFillSettled().getOrderId())
                .as("never the counterparty's order")
                .isNotEqualTo(bytes(trade.getSellerOrderId()));
        assertThat(buyers.getFillSettled().getFee()).isEqualTo(700L);

        assertThat(sellers.getUserId()).isEqualTo(SELLER);
        assertThat(sellers.getFillSettled().getOrderId()).isEqualTo(bytes(trade.getSellerOrderId()));
        assertThat(sellers.getFillSettled().getOrderId())
                .as("never the counterparty's order")
                .isNotEqualTo(bytes(trade.getBuyerOrderId()));
        assertThat(sellers.getFillSettled().getFee()).isEqualTo(-200L);

        // And the structural guarantee behind all of it: FillSettled has no field for a
        // counterparty, so there is nothing to leak even by accident. This fails to compile
        // if anyone adds one.
        assertThat(buyers.getFillSettled().getAllFields().keySet())
                .extracting(com.google.protobuf.Descriptors.FieldDescriptor::getName)
                .doesNotContain("maker_user_id", "taker_user_id", "counterparty_id");
    }

    /**
     * The maker flag is the buyer's, so the seller's is its opposite. Read straight across and
     * a taker is reported as a maker -- and since the two pay different rates, the fee beside
     * it then contradicts the flag.
     */
    @Test
    void themakerFlagIsInvertedForTheSeller() throws Exception {
        publisher.publishAfterCommit(SYMBOL, List.of(trade(true, -200L, 700L)));

        commit();

        assertThat(sentTo("11").getFillSettled().getIsMaker()).as("buyer rested").isTrue();
        assertThat(sentTo("22").getFillSettled().getIsMaker()).as("seller crossed").isFalse();
    }

    @Test
    void eachSideCarriesItsOwnDirectionAndFeeAsset() throws Exception {
        publisher.publishAfterCommit(SYMBOL, List.of(trade(false, 700L, -200L)));

        commit();

        assertThat(sentTo("11").getFillSettled().getSide()).isEqualTo(Side.SIDE_BUY);
        assertThat(sentTo("11").getFillSettled().getFeeAssetId()).isEqualTo(BASE_ASSET);
        assertThat(sentTo("22").getFillSettled().getSide()).isEqualTo(Side.SIDE_SELL);
        assertThat(sentTo("22").getFillSettled().getFeeAssetId()).isEqualTo(QUOTE_ASSET);
    }

    /** A redelivered batch inserts nothing, so there is nothing to announce. Announcing would
     *  tell a client one fill happened twice. */
    @Test
    void nothingInsertedMeansNothingAnnounced() {
        publisher.publishAfterCommit(SYMBOL, List.of());

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void manyFillsInOneBatchEachGetTheirOwnPairOfMessages() {
        publisher.publishAfterCommit(SYMBOL, List.of(
                trade(true, 700L, -200L), trade(true, 700L, -200L), trade(true, 700L, -200L)));

        commit();

        verify(kafka, timeout(2_000).times(6)).send(anyString(), anyString(), any());
    }

    // --- helpers ---

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    /** The message sent to one recipient, waiting for the executor to get there. */
    private Settlement sentTo(String key) throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(kafka, timeout(2_000).atLeastOnce())
                .send(eq(Topics.SPOT_SETTLEMENTS), eq(key), payload.capture());
        return Settlement.parseFrom(payload.getValue());
    }

    private static com.google.protobuf.ByteString bytes(UUID id) {
        return com.google.protobuf.ByteString.copyFrom(com.exchange.common.id.Ulid.toBytes(id));
    }

    private static SpotTrade trade(boolean buyerIsMaker, long buyerFee, long sellerFee) {
        SpotTrade trade = new SpotTrade(UUID.randomUUID(), 5L, 100 * ONE, ONE,
                UUID.randomUUID(), UUID.randomUUID(), BUYER, SELLER, buyerIsMaker, 1L,
                Instant.EPOCH);
        trade.chargeFees(buyerFee, BASE_ASSET, sellerFee, QUOTE_ASSET);
        return trade;
    }
}
