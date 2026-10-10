package com.exchange.user.identity.external;

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

/** A link between one exchange account and one account at an identity provider. */
@Entity
@Table(name = "user_external_identities")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserExternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExternalProvider provider;

    /** The provider's immutable id -- Google's {@code sub}. Never the email; see V1__user.sql. */
    @Column(nullable = false, length = 255)
    private String subject;

    /** What the provider said the address was when linked. Support and audit only, never
     *  a lookup key: it drifts from both the provider's current value and ours. */
    @Column(length = 255)
    private String email;

    @Column(nullable = false)
    private Instant linkedAt = Instant.now();

    private Instant lastLoginAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public UserExternalIdentity(Long userId, ExternalProvider provider, String subject,
                                String email) {
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserExternalIdentity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
