package com.exchange.spot.symbol;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.kafka.Topics;
import com.exchange.spot.order.exception.MarketUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaAdmin;

@ExtendWith(MockitoExtension.class)
class CommandPartitionsTest {

    @Mock
    private SpotSymbolRepository spotSymbols;

    @Mock
    private KafkaAdmin kafkaAdmin;

    private CommandPartitions partitions() {
        return new CommandPartitions(spotSymbols, kafkaAdmin);
    }

    private static SpotSymbol market(String code, int partition) {
        return new SpotSymbol(code, 1L, 2L, 1L, 1L, 1L, 10L, 1L, partition);
    }

    private static Map<String, TopicDescription> topicWith(int partitions) {
        return Map.of(Topics.SPOT_COMMANDS, new TopicDescription(Topics.SPOT_COMMANDS, false,
                IntStream.range(0, partitions)
                        .mapToObj(i -> new TopicPartitionInfo(i, null, List.of(), List.of()))
                        .toList()));
    }

    @Test
    void startupRefusesToStartWithAMarketPastTheLastPartition() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)).thenReturn(topicWith(8));
        when(spotSymbols.findAll()).thenReturn(List.of(market("BTCUSDT", 0), market("TYPOUSDT", 8)));

        assertThatThrownBy(() -> partitions().verifyEveryMarket())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TYPOUSDT -> 8")
                .hasMessageNotContaining("BTCUSDT");
    }

    @Test
    void startupPassesWhenEveryPartitionExists() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)).thenReturn(topicWith(8));
        when(spotSymbols.findAll()).thenReturn(List.of(market("BTCUSDT", 0), market("TRXUSDT", 7)));

        assertThatCode(() -> partitions().verifyEveryMarket()).doesNotThrowAnyException();
    }

    /** The engine creates the topic; an API that starts first must not be blocked by that. */
    @Test
    void startupBeforeTheTopicExistsDoesNotBlock() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)).thenThrow(new KafkaException(
                "no topic", new ExecutionException(new UnknownTopicOrPartitionException("missing"))));

        assertThatCode(() -> partitions().verifyEveryMarket()).doesNotThrowAnyException();
        verify(spotSymbols, never()).findAll();
    }

    @Test
    void anOrderOnAMarketPastTheLastPartitionIsRefusedAfterReReading() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)).thenReturn(topicWith(8));
        CommandPartitions partitions = partitions();
        when(spotSymbols.findAll()).thenReturn(List.of());
        partitions.verifyEveryMarket();

        assertThatThrownBy(() -> partitions.requireExists(market("NEWUSDT", 9)))
                .isInstanceOf(MarketUnavailableException.class);
        // Once at startup, once more before refusing: the topic may have grown since.
        verify(kafkaAdmin, times(2)).describeTopics(Topics.SPOT_COMMANDS);
    }

    @Test
    void aTopicThatGrewIsReReadRatherThanRefused() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS))
                .thenReturn(topicWith(8)).thenReturn(topicWith(16));
        CommandPartitions partitions = partitions();
        when(spotSymbols.findAll()).thenReturn(List.of());
        partitions.verifyEveryMarket();

        assertThatCode(() -> partitions.requireExists(market("NEWUSDT", 9))).doesNotThrowAnyException();
    }

    /** The order path's common case: no broker call at all. */
    @Test
    void aPartitionKnownToExistCostsNoBrokerCall() {
        when(kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)).thenReturn(topicWith(8));
        CommandPartitions partitions = partitions();
        when(spotSymbols.findAll()).thenReturn(List.of());
        partitions.verifyEveryMarket();

        partitions.requireExists(market("BTCUSDT", 0));
        partitions.requireExists(market("TRXUSDT", 7));
        verify(kafkaAdmin, times(1)).describeTopics(Topics.SPOT_COMMANDS);
    }
}
