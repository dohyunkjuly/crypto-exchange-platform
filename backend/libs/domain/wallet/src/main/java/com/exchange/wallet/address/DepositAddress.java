package com.exchange.wallet.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Where a user sends funds to deposit, for one asset on one chain.
 *
 * <p>Derived from a master seed rather than stored as a key — {@code derivationPath} is
 * enough to re-derive it, and the seed never touches this database.
 *
 * <p>{@code memo} is {@code ""} rather than null on chains that do not use one. XRP, XLM
 * and EOS share a single platform address across every user and route by memo, so
 * uniqueness is over (address, memo) — and MySQL permits duplicate rows when part of a
 * unique key is NULL, which would let the same address be issued twice.
 */
@Entity
@Table(name = "wallet_deposit_addresses")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositAddress {

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

    /** BIP-44, e.g. {@code m/44'/60'/0'/0/12345}. */
    @Column(length = 64)
    @Setter(AccessLevel.NONE)
    private String derivationPath;

    @Setter(AccessLevel.NONE)
    private Long derivationIndex;

    /** Rotated rather than deleted: funds sent to an old address still arrive. */
    @Column(nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public DepositAddress(Long userId, Long assetNetworkId, String address, String memo,
                          String derivationPath, Long derivationIndex) {
        this.userId = userId;
        this.assetNetworkId = assetNetworkId;
        this.address = address;
        this.memo = memo == null ? "" : memo;
        this.derivationPath = derivationPath;
        this.derivationIndex = derivationIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DepositAddress other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
