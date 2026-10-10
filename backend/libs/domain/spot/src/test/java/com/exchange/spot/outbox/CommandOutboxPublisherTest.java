package com.exchange.spot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class CommandOutboxPublisherTest {

    private static final long USER = 7L;

    @Mock
    private SpotCommandOutboxRepository rows;
    @Mock
    private KafkaTemplate<String, byte[]> kafka;

    private CommandOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        // Zero lanes: batches are published on this thread, so a test sees them settle.
        publisher = new CommandOutboxPublisher(rows, kafka, 0, 10, 3);
        lenient().when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private static SpotCommandOutbox row(UUID orderId) {
        return new SpotCommandOutbox(UUID.randomUUID(), "BTCUSDT", 3, orderId, USER, new byte[] {1});
    }

    private static SpotCommandOutbox behind(UUID orderId) {
        SpotCommandOutbox row = row(orderId);
        row.markBehindAnother();
        return row;
    }

    @Test
    @DisplayName("a batch is sent, and what the broker acknowledged is stamped in one update")
    void a_batch_is_sent_and_stamped_together() {
        SpotCommandOutbox one = row(UUID.randomUUID());
        SpotCommandOutbox two = row(UUID.randomUUID());

        assertThat(publisher.publishBatch(List.of(one, two))).isEqualTo(2);

        verify(kafka, times(2)).send(eq("exchange.spot.commands.v1"), eq(3), eq("BTCUSDT"), any(byte[].class));
        verify(rows).markPublishedAll(eq(List.of(one.getId(), two.getId())), any());
    }

    @Test
    @DisplayName("a command the broker did not acknowledge is left unstamped, for the sweeper")
    void an_unacknowledged_command_is_not_stamped() {
        SpotCommandOutbox lost = row(UUID.randomUUID());
        SpotCommandOutbox landed = row(UUID.randomUUID());
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafka.send(anyString(), anyInt(), anyString(), eq(lost.getPayload()))).thenReturn(failed);
        // Same payload bytes would match the stub above, so give the other its own.
        SpotCommandOutbox other = new SpotCommandOutbox(landed.getId(), "BTCUSDT", 3, landed.getOrderId(),
                USER, new byte[] {2});

        assertThat(publisher.publishBatch(List.of(lost, other))).isEqualTo(1);

        verify(rows).markPublishedAll(eq(List.of(other.getId())), any());
    }

    @Test
    @DisplayName("nothing acknowledged means nothing stamped, not an empty update")
    void nothing_acknowledged_stamps_nothing() {
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class))).thenReturn(failed);

        assertThat(publisher.publishBatch(List.of(row(UUID.randomUUID())))).isZero();

        verify(rows, never()).markPublishedAll(any(), any());
    }

    /** A place and its cancel in flight together could land in either order if one of the two
     *  sends had to be retried. Cut apart, the place is settled before the cancel is looked at. */
    @Test
    @DisplayName("a batch stops short of a second command for an order it already holds")
    void a_batch_is_cut_where_an_order_appears_twice() throws InterruptedException {
        UUID order = UUID.randomUUID();
        SpotCommandOutbox place = row(order);
        SpotCommandOutbox other = row(UUID.randomUUID());
        SpotCommandOutbox cancel = row(order);
        BlockingQueue<SpotCommandOutbox> lane = new LinkedBlockingQueue<>(List.of(place, other, cancel));

        assertThat(publisher.nextBatch(lane)).containsExactly(place, other);
        assertThat(publisher.nextBatch(lane)).containsExactly(cancel);
    }

    @Test
    @DisplayName("two cancel-alls of one user are ordered against each other too, though neither names an order")
    void cancel_alls_of_one_user_are_cut_apart() throws InterruptedException {
        BlockingQueue<SpotCommandOutbox> lane = new LinkedBlockingQueue<>(List.of(row(null), row(null)));

        assertThat(publisher.nextBatch(lane)).hasSize(1);
    }

    @Test
    @DisplayName("a batch takes no more than its limit, and leaves the rest queued in order")
    void a_batch_is_bounded() throws InterruptedException {
        List<SpotCommandOutbox> five = List.of(row(UUID.randomUUID()), row(UUID.randomUUID()),
                row(UUID.randomUUID()), row(UUID.randomUUID()), row(UUID.randomUUID()));
        BlockingQueue<SpotCommandOutbox> lane = new LinkedBlockingQueue<>(five);

        assertThat(publisher.nextBatch(lane)).containsExactlyElementsOf(five.subList(0, 3));
        assertThat(lane).containsExactlyElementsOf(five.subList(3, 5));
    }

    /** The engine answers a cancel for an order it has never seen with silence, and the order
     *  then rests with its owner believing it gone. */
    @Test
    @DisplayName("a command is not sent while an older one for its order is still unpublished")
    void a_command_never_overtakes_an_older_one_for_its_order() {
        UUID order = UUID.randomUUID();
        SpotCommandOutbox cancel = behind(order);
        when(rows.hasOlderPendingForOrder(order, cancel.getCreatedAt(), cancel.getId())).thenReturn(true);

        assertThat(publisher.publishBatch(List.of(cancel))).isZero();

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("once the older one has been stamped, the one behind it goes")
    void it_goes_once_the_older_one_is_published() {
        UUID order = UUID.randomUUID();
        SpotCommandOutbox cancel = behind(order);
        when(rows.hasOlderPendingForOrder(order, cancel.getCreatedAt(), cancel.getId())).thenReturn(false);

        assertThat(publisher.publishBatch(List.of(cancel))).isEqualTo(1);
    }

    @Test
    @DisplayName("a command with nothing ahead of it when it was written is sent without asking")
    void a_command_with_nothing_ahead_costs_no_query() {
        publisher.publishBatch(List.of(row(UUID.randomUUID())));

        verify(rows, never()).hasOlderPendingForOrder(any(), any(), any());
    }

    @Test
    @DisplayName("a cancel-all waits behind anything older of its user, since it names no order")
    void a_cancel_all_asks_about_its_user() {
        SpotCommandOutbox cancelAll = behind(null);
        when(rows.hasOlderPendingForUser(USER, cancelAll.getCreatedAt(), cancelAll.getId())).thenReturn(true);

        assertThat(publisher.publishBatch(List.of(cancelAll))).isZero();
    }

    @Test
    @DisplayName("a full lane refuses the row rather than blocking the request that brought it")
    void a_full_lane_refuses() {
        CommandOutboxPublisher oneSlot = new CommandOutboxPublisher(rows, kafka, 1, 1, 3);   // never started

        assertThat(oneSlot.submit(row(UUID.randomUUID()))).isTrue();
        assertThat(oneSlot.submit(row(UUID.randomUUID()))).isFalse();
    }
}
