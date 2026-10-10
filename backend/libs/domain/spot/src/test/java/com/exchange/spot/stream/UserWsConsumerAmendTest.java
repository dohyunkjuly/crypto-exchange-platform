package com.exchange.spot.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAmendRejected;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.marketdata.stream.WsMessage;
import com.google.protobuf.ByteString;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserWsConsumerAmendTest {

    private static final long ONE = 100_000_000L;
    private static final ByteString ORDER_ID = ByteString.copyFrom(Ulid.toBytes(UUID.randomUUID()));

    private final UserFanout sockets = mock(UserFanout.class);
    private final UserWsConsumer consumer = new UserWsConsumer(sockets);

    private OrderUpdateData frameFor(long userId, Event.Builder event) {
        consumer.onEvent(new ConsumerRecord<>(Topics.SPOT_EVENTS, 0, 0, "BTCUSDT",
                event.setSymbol("BTCUSDT").build().toByteArray()));
        ArgumentCaptor<WsMessage> sent = ArgumentCaptor.forClass(WsMessage.class);
        verify(sockets).sendTo(eq(userId), sent.capture());
        return (OrderUpdateData) sent.getValue().data();
    }

    @Test
    @DisplayName("an applied amend tells its owner the new terms and what is left")
    void amended() {
        OrderUpdateData frame = frameFor(7, Event.newBuilder().setOrderAmended(
                OrderAmended.newBuilder().setOrderId(ORDER_ID).setUserId(7)
                        .setNewPrice(110 * ONE).setNewQuantity(3 * ONE)
                        .setNewRemainingQuantity(2 * ONE)));

        assertThat(frame.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(frame.remainingQuantity()).isEqualTo("2");
        assertThat(frame.amend().rejectReason()).isNull();
        assertThat(frame.amend().price()).isEqualTo("110");
        assertThat(frame.amend().quantity()).isEqualTo("3");
        assertThat(frame.amend().stopPrice()).isNull();
    }

    @Test
    @DisplayName("a refused amend carries the reason and claims nothing about the order's status")
    void amendRejected() {
        OrderUpdateData frame = frameFor(7, Event.newBuilder().setOrderAmendRejected(
                OrderAmendRejected.newBuilder().setOrderId(ORDER_ID).setUserId(7)
                        .setReason(AmendRejectReason.AMEND_REJECT_REASON_WOULD_TAKE)));

        assertThat(frame.status()).isNull();
        assertThat(frame.amend().rejectReason()).isEqualTo("AMEND_REJECT_REASON_WOULD_TAKE");
    }
}
