package com.exchange.user.kyc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock private UserKycProfileRepository profiles;
    @Mock private UserRepository users;
    @Mock private UserAuthEventRepository authEvents;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private KycService kyc;

    @Test
    @DisplayName("an unverified email cannot submit KYC, and nothing is written")
    void unverifiedCannotSubmit() {
        User user = new User("t@example.com", "hash");
        user.setEmailVerified(false);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> kyc.submit(7L, "A", "B", LocalDate.of(1990, 1, 1), "KR", "KR", "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);

        // Identity checks start from a mailbox we know the user holds. Refusing must leave
        // no half-written PENDING profile behind.
        verify(profiles, never()).save(any());
        verify(events, never()).publishEvent(any());
    }
}
