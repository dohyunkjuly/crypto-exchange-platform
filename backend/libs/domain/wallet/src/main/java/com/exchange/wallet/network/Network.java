package com.exchange.wallet.network;

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

/** A chain: Ethereum, Tron, Bitcoin, Solana. */
@Entity
@Table(name = "wallet_networks")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Network {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    @Setter(AccessLevel.NONE)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 1 Ethereum, 56 BSC, 137 Polygon. Null for anything not EVM. */
    private Long evmChainId;

    @Column(length = 255)
    private String explorerTxUrl;

    @Column(length = 255)
    private String explorerAddressUrl;

    /** Testnets live here too. Crediting a real balance from one is how money is lost. */
    @Column(nullable = false)
    private boolean isTestnet = false;

    @Column(nullable = false)
    private boolean isActive = false;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public Network(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Network other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
