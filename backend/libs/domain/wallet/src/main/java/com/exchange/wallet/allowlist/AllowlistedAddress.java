package com.exchange.wallet.allowlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A saved withdrawal destination.
 *
 * <p>{@code activatesAt} is the point of the whole table. A newly added address is unusable
 * for a cooling period, so an attacker who gets into an account cannot add their own
 * address and withdraw in the same session — the delay gives the notification time to
 * reach a human who did not add it.
 */
@Entity
@Table(name = "wallet_address_allowlist")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllowlistedAddress {

    /** How long a new destination stays unusable. */
    public static final Duration COOLING_PERIOD = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long assetNetworkId;

    @Column(nullable = false, length = 128)
    @Setter(AccessLevel.NONE)
    private String address;

    @Column(nullable = false, length = 64)
    @Setter(AccessLevel.NONE)
    private String memo = "";

    @Column(length = 64)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AllowlistStatus status = AllowlistStatus.PENDING;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant activatesAt;

    private Instant removedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public AllowlistedAddress(Long userId, Long assetNetworkId, String address, String memo,
                              String label, Instant now) {
        this.userId = userId;
        this.assetNetworkId = assetNetworkId;
        this.address = address;
        this.memo = memo == null ? "" : memo;
        this.label = label;
        this.activatesAt = now.plus(COOLING_PERIOD);
    }

    public boolean isUsable(Instant now) {
        return status == AllowlistStatus.ACTIVE
                && removedAt == null
                && !activatesAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AllowlistedAddress other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
