package com.exchange.spot.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * One command waiting to reach the engine, written in the same transaction as the order
 * change it describes. Why the API publishes this way: {@link CommandOutboxService}.
 *
 * <p>Holds the serialized command, not the fields to rebuild it from: a re-send has to carry
 * the same {@code command_id} for the engine to know it as a repeat, and an amend's new terms
 * are nowhere else until settlement applies them.
 *
 * <p>Written once, then {@code publishedAt} is set once; nothing else ever changes. The id is
 * assigned (it is the command's), so {@link Persistable} says it is new rather than Hibernate
 * checking, as {@code SpotOrder} does.
 */
@Entity
@Table(name = "spot_command_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotCommandOutbox implements Persistable<UUID> {

    @Id
    private UUID id;

    private String symbol;

    private int commandPartition;

    /** Null for a cancel-all, which names a user rather than an order. */
    private UUID orderId;

    private Long userId;

    @Column(name = "payload")
    private byte[] payload;

    private Instant createdAt;

    private Instant publishedAt;

    @Transient
    private boolean isNew = false;

    /** An older command for the same order was unpublished when this was written, so the
     *  publisher must check it has gone before sending this one. Not stored: it is a note from
     *  the transaction that wrote the row to the publisher it hands the row to. */
    @Transient
    private boolean behindAnother = false;

    public SpotCommandOutbox(UUID commandId, String symbol, int commandPartition, UUID orderId,
                             Long userId, byte[] payload) {
        this.id = commandId;
        this.symbol = symbol;
        this.commandPartition = commandPartition;
        this.orderId = orderId;
        this.userId = userId;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    void markBehindAnother() {
        this.behindAnother = true;
    }
}
