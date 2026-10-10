package com.exchange.wallet.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Something that can be held: BTC, USDT, USD.
 *
 * <p>An asset is not a network. USDT is one asset living on Ethereum, Tron and Solana with
 * different contracts and fees on each — see {@code AssetNetwork}.
 */
@Entity
@Table(name = "wallet_assets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    @Setter(AccessLevel.NONE)
    private String symbol;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private AssetType assetType;

    /** Display precision. Storage is always scaled by 1e8. */
    @Column(nullable = false)
    private int displayDecimals;

    /** Three switches, not one: a chain upgrade halts deposits while trading continues. */
    @Column(nullable = false)
    private boolean tradingEnabled = false;

    @Column(nullable = false)
    private boolean depositEnabled = false;

    @Column(nullable = false)
    private boolean withdrawalEnabled = false;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public Asset(String symbol, String name, AssetType assetType, int displayDecimals) {
        this.symbol = symbol;
        this.name = name;
        this.assetType = assetType;
        this.displayDecimals = displayDecimals;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Asset other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
