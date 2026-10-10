package com.exchange.spot.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.stream.WsMessage;
import com.exchange.wallet.asset.AssetNames;
import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.BalanceChanged;
import com.exchange.contracts.spot.v1.FillSettled;
import com.exchange.contracts.spot.v1.Settlement;
import com.exchange.contracts.spot.v1.Side;
import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Decoding a settlement into a frame, and who it goes to.
 *
 * <p>The routing is the socket's job and tested there; what matters here is that the
 * user id on the wire is the one the frame is addressed to, and that the values survive the
 * translation -- particularly a negative fee, which has been misreported at every previous layer
 * of this chain.
 */
@ExtendWith(MockitoExtension.class)
class SettlementConsumerTest {

    private static final long USER = 42L;
    private static final long ASSET_ID = 7L;
    private static final long ONE = 100_000_000L;

    @Mock
    private UserFanout sockets;

    @Mock
    private AssetNames assetNames;

    private SettlementConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SettlementConsumer(sockets, assetNames);
    }

    @Test
    void abalanceChangeGoesToItsOwnerWithTheAssetNamed() {
        when(assetNames.of(ASSET_ID)).thenReturn(Optional.of("USDT"));

        consume(Settlement.newBuilder()
                .setUserId(USER)
                .setBalanceChanged(BalanceChanged.newBuilder()
                        .setAssetId(ASSET_ID).setFree(900 * ONE).setLocked(100 * ONE)
                        .setVersion(6))
                .build());

        BalanceChangedData data = (BalanceChangedData) captureFor(USER, "balanceChanged").data();
        assertThat(data.asset()).isEqualTo("USDT");
        assertThat(data.free()).isEqualTo("900");
        assertThat(data.locked()).isEqualTo("100");
        assertThat(data.version()).isEqualTo(6L);
    }

    /**
     * The version is what lets a client order a feed several processes publish to. Dropping it
     * would not fail anything visibly -- the client would simply apply a stale balance over a
     * fresh one and stay wrong until that account next changed.
     */
    @Test
    void theversionSurvivesOntoTheFrame() {
        when(assetNames.of(ASSET_ID)).thenReturn(Optional.of("USDT"));

        consume(balanceChange(11));

        assertThat(((BalanceChangedData) captureFor(USER, "balanceChanged").data()).version())
                .isEqualTo(11L);
    }

    /** An asset listed since the last cache refresh. Rendering a blank name would put an
     *  unidentifiable balance on screen; the row is still correct in MySQL. */
    @Test
    void abalanceChangeForAnUnknownAssetIsDropped() {
        when(assetNames.of(ASSET_ID)).thenReturn(Optional.empty());

        consume(balanceChange(6));

        verify(sockets, never()).sendTo(anyLong(), any());
    }

    /**
     * A maker rebate is a negative fee and must reach the browser negative. Every layer below
     * this reported a charge instead until it was fixed -- the engine sends rates, settlement
     * computes the amount, a CHECK constraint forbade the sign, and the column stored a
     * magnitude.
     */
    @Test
    void amakerRebateReachesTheClientAsANegativeFee() {
        when(assetNames.of(ASSET_ID)).thenReturn(Optional.of("BTC"));

        consume(Settlement.newBuilder()
                .setUserId(USER)
                .setFillSettled(fill(-2_000L))
                .build());

        FillData data = (FillData) captureFor(USER, "fill").data();
        assertThat(data.fee()).startsWith("-");
        assertThat(data.feeAsset()).isEqualTo("BTC");
        assertThat(data.isMaker()).isTrue();
        assertThat(data.side()).isEqualTo("SELL");
    }

    /** A zero fee writes no asset, so the id is absent. That is not a reason to drop a fill --
     *  the fill itself is the information. */
    @Test
    void afillWithNoFeeAssetStillReachesTheClient() {
        when(assetNames.of(anyLong())).thenReturn(Optional.empty());

        consume(Settlement.newBuilder().setUserId(USER).setFillSettled(fill(0L)).build());

        FillData data = (FillData) captureFor(USER, "fill").data();
        assertThat(data.fee()).isEqualTo("0");
        assertThat(data.feeAsset()).isNull();
    }

    /** A frame is addressed by the user id on the message, never by anything else -- this is the
     *  only thing standing between one account's settlements and another's. */
    @Test
    void aframeIsAddressedToTheUserOnTheMessage() {
        when(assetNames.of(ASSET_ID)).thenReturn(Optional.of("USDT"));

        consume(Settlement.newBuilder().setUserId(999L)
                .setBalanceChanged(BalanceChanged.newBuilder().setAssetId(ASSET_ID).setVersion(1))
                .build());

        verify(sockets).sendTo(eq(999L), any());
        verify(sockets, never()).sendTo(eq(USER), any());
    }

    /** A malformed record can never parse, so retrying would stall the stream over one bad
     *  byte. Skip it; nothing downstream depends on it. */
    @Test
    void amalformedRecordIsSkipped() {
        consumer.onSettlement(new ConsumerRecord<>(Topics.SPOT_SETTLEMENTS, 0, 0L, "1",
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));

        verify(sockets, never()).sendTo(anyLong(), any());
    }

    // --- helpers ---

    private void consume(Settlement settlement) {
        consumer.onSettlement(new ConsumerRecord<>(Topics.SPOT_SETTLEMENTS, 0, 0L,
                Long.toString(settlement.getUserId()), settlement.toByteArray()));
    }

    private WsMessage<?> captureFor(long userId, String type) {
        ArgumentCaptor<WsMessage<?>> frame = ArgumentCaptor.captor();
        verify(sockets).sendTo(eq(userId), frame.capture());
        assertThat(frame.getValue().type()).isEqualTo(type);
        return frame.getValue();
    }

    private static Settlement balanceChange(long version) {
        return Settlement.newBuilder()
                .setUserId(USER)
                .setBalanceChanged(BalanceChanged.newBuilder()
                        .setAssetId(ASSET_ID).setFree(ONE).setLocked(0).setVersion(version))
                .build();
    }

    private static FillSettled fill(long fee) {
        return FillSettled.newBuilder()
                .setTradeId(ByteString.copyFrom(Ulid.toBytes(UUID.randomUUID())))
                .setOrderId(ByteString.copyFrom(Ulid.toBytes(UUID.randomUUID())))
                .setSymbol("BTCUSDT")
                .setPrice(100 * ONE)
                .setQuantity(ONE)
                .setQuoteQuantity(100 * ONE)
                .setSide(Side.SIDE_SELL)
                .setIsMaker(true)
                .setFee(fee)
                .setFeeAssetId(fee == 0 ? 0 : ASSET_ID)
                .build();
    }
}
