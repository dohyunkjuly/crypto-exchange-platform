package com.exchange.bff.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes the email to the log instead of sending it.
 *
 * <p>This is how verification links are obtained in development and in tests: no mail
 * server, no credentials, no network. It exists so the whole signup flow is buildable and
 * testable before a provider is chosen.
 *
 * <p>Selected by {@code exchange.mail.mode}, which defaults to {@code log}. Setting it to
 * anything else leaves this bean out, so a real {@link Mailer} -- SES, SendGrid, SMTP --
 * takes over with no other change. Deliberately a property rather than
 * {@code @ConditionalOnMissingBean}: that annotation is evaluated during
 * auto-configuration, after component scanning, so on a plain {@code @Component} it does
 * not reliably see beans defined by the application itself.
 *
 * <p><b>It logs the body, which contains a live verification link.</b> That is the point
 * in development and unacceptable in production, so a real implementation must be
 * configured before this is deployed anywhere with real users. The warning below is
 * logged on every send so the situation cannot go unnoticed in a running system.
 */
@Component
@ConditionalOnProperty(name = "exchange.mail.mode", havingValue = "log", matchIfMissing = true)
public class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn("NO MAILER CONFIGURED -- logging instead of sending.\n"
                + "  to:      {}\n"
                + "  subject: {}\n"
                + "  body:\n{}", to, subject, body);
    }
}
