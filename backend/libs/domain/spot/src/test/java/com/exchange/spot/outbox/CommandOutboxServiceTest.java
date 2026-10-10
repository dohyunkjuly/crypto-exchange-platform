package com.exchange.spot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.CancelOrder;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.spot.symbol.SpotSymbol;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class CommandOutboxServiceTest {

    private static final long USER = 7L;

    @Mock
    private SpotCommandOutboxRepository rows;
    @Mock
    private KafkaTemplate<String, byte[]> kafka;

    private CommandOutboxService outbox;
    private SpotSymbol market;

    @BeforeEach
    void setUp() {
        // Zero lanes: handing a row off publishes it on this thread.
        outbox = new CommandOutboxService(rows, kafka, new CommandOutboxPublisher(rows, kafka, 0, 10, 500));
        market = new SpotSymbol("BTCUSDT", 1L, 2L, 1, 1, 1, 1_000_000, 1, 3);
        lenient().when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private static Command cancel(UUID orderId) {
        return Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setSymbol("BTCUSDT")
                .setCancelOrder(CancelOrder.newBuilder()
                        .setOrderId(ByteString.copyFrom(Ulid.toBytes(orderId))).setUserId(USER))
                .build();
    }

    private static Command cancelAll() {
        return Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setSymbol("BTCUSDT")
                .setCancelAllOrders(CancelAllOrders.newBuilder().setUserId(USER))
                .build();
    }

    @Test
    @DisplayName("enqueue only writes the row; nothing is sent until it is handed off")
    void enqueue_writes_and_hand_off_sends() {
        UUID orderId = UUID.randomUUID();
        Command command = cancel(orderId);

        SpotCommandOutbox row = outbox.enqueue(market, orderId, USER, command);
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
        outbox.handOff(row);

        ArgumentCaptor<SpotCommandOutbox> saved = ArgumentCaptor.forClass(SpotCommandOutbox.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getId())
                .as("the row is the command: one id, so a re-send is a repeat to the engine")
                .isEqualTo(Ulid.toUuid(command.getCommandId().toByteArray()));
        assertThat(saved.getValue().getPayload()).isEqualTo(command.toByteArray());
        verify(kafka).send(eq("exchange.spot.commands.v1"), eq(3), eq("BTCUSDT"), eq(command.toByteArray()));
        verify(rows).markPublishedAll(eq(List.of(row.getId())), any());
    }

    @Test
    @DisplayName("a row written behind an unpublished one for the same order says so")
    void a_row_behind_another_is_flagged() {
        UUID orderId = UUID.randomUUID();
        when(rows.hasOtherPendingForOrder(eq(orderId), any())).thenReturn(true);

        assertThat(outbox.enqueue(market, orderId, USER, cancel(orderId)).isBehindAnother()).isTrue();
        assertThat(outbox.enqueue(market, UUID.randomUUID(), USER, cancel(orderId)).isBehindAnother()).isFalse();
    }

    @Test
    @DisplayName("a cancel-all is behind anything unpublished of its user, since it names no order")
    void a_cancel_all_is_flagged_by_user() {
        when(rows.hasOtherPendingForUser(eq(USER), any())).thenReturn(true);

        assertThat(outbox.enqueue(market, null, USER, cancelAll()).isBehindAnother()).isTrue();
    }

    /** The row is not committed yet: a command Kafka has for an order the database may still
     *  roll back is the phantom order this class exists to prevent. */
    @Test
    @DisplayName("inside a transaction a hand-off sends nothing, and the row is left for the sweeper")
    void a_hand_off_inside_a_transaction_does_nothing() {
        SpotCommandOutbox row = outbox.enqueue(market, UUID.randomUUID(), USER, cancel(UUID.randomUUID()));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            outbox.handOff(row);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(kafka, never()).send(anyString(), anyInt(), anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("the sweeper's publish waits for the broker and stamps the one row")
    void the_sweepers_publish_stamps_one_row() {
        SpotCommandOutbox row = row("BTCUSDT");

        assertThat(outbox.publish(row)).isTrue();
        verify(rows).markPublished(eq(row.getId()), any());
    }

    @Test
    @DisplayName("a send the broker refuses leaves the row unstamped and raises nothing")
    void a_failed_publish_is_left_for_the_sweeper() {
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafka.send(anyString(), anyInt(), anyString(), any(byte[].class))).thenReturn(failed);

        assertThat(outbox.publish(row("BTCUSDT"))).isFalse();
        verify(rows, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("the sweeper keeps a market's order: after one failure the rest of that market waits")
    void the_sweeper_stalls_a_market_after_a_failure_and_carries_on_with_others() {
        SpotCommandOutbox btcFirst = row("BTCUSDT");
        SpotCommandOutbox btcSecond = row("BTCUSDT");
        SpotCommandOutbox eth = row("ETHUSDT");
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("BTCUSDT's partition is unavailable"));
        when(kafka.send(anyString(), anyInt(), eq("BTCUSDT"), any(byte[].class))).thenReturn(failed);

        CommandOutboxSweeper sweeper = new CommandOutboxSweeper(rows, outbox, 10_000, 500, 3_600_000);
        int published = sweeper.publishInOrder(List.of(btcFirst, btcSecond, eth));

        assertThat(published).isEqualTo(1);
        verify(kafka).send(anyString(), anyInt(), eq("BTCUSDT"), eq(btcFirst.getPayload()));
        verify(kafka, never()).send(anyString(), anyInt(), eq("BTCUSDT"), eq(btcSecond.getPayload()));
        verify(rows).markPublished(eq(eth.getId()), any());
        verify(rows, never()).markPublished(eq(btcFirst.getId()), any());
    }

    private static SpotCommandOutbox row(String symbol) {
        Command command = Command.newBuilder()
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setSymbol(symbol)
                .setCancelAllOrders(CancelAllOrders.newBuilder().setUserId(USER))
                .build();
        return new SpotCommandOutbox(Ulid.toUuid(command.getCommandId().toByteArray()), symbol, 0,
                null, USER, command.toByteArray());
    }
}
