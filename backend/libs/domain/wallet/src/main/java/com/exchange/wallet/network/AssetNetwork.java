package com.exchange.wallet.network;

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
 * One asset on one chain — USDT on Tron, USDT on Ethereum. The row a deposit address or a
 * withdrawal actually belongs to.
 *
 * <p>Withdrawals must resolve one of these rather than reasoning about the asset alone:
 * sending TRC-20 USDT to an ERC-20 address is unrecoverable.
 *
 * <p>Plain {@code assetId} and {@code networkId} rather than {@code @ManyToOne}. Both live
 * in this domain so a relationship would be legal, but nothing here needs to walk the
 * graph, and a lazy proxy that explodes outside a transaction is a worse trade than a
 * second lookup.
 */
@Entity
@Table(name = "wallet_asset_networks")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetNetwork {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long assetId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long networkId;

    /** Null for the chain's native coin. */
    @Column(length = 128)
    private String contractAddress;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TokenStandard tokenStandard;

    /**
     * What the chain uses — 18 for ETH, 6 for USDT. Balances are scaled by 1e8, so this is
     * the conversion factor on the deposit path, and why anything below 1e-8 is dust.
     */
    @Column(nullable = false)
    private int chainDecimals;

    /** All scaled by 1e8. */
    @Column(nullable = false)
    private long minDeposit;

    @Column(nullable = false)
    private long minWithdrawal;

    @Column(nullable = false)
    private long withdrawalFee;

    @Column(nullable = false)
    private int requiredConfirmations;

    /** Last cheap place to catch a typo'd destination. */
    @Column(length = 255)
    private String addressRegex;

    /** XRP, XLM and others share one address and route by memo. */
    @Column(nullable = false)
    private boolean requiresMemo = false;

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

    public AssetNetwork(Long assetId, Long networkId, int chainDecimals, int requiredConfirmations) {
        this.assetId = assetId;
        this.networkId = networkId;
        this.chainDecimals = chainDecimals;
        this.requiredConfirmations = requiredConfirmations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssetNetwork other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
