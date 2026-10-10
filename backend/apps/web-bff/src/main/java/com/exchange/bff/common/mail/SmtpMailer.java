package com.exchange.bff.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends over SMTP.
 *
 * <p>SMTP rather than a provider's HTTP API, so that <b>the same code path runs in
 * development and in production</b>: against Mailpit on localhost here, against
 * SendGrid/SES/Postmark's relay there, with only a host and a credential between them. An
 * API client would mean every local send exercised code no real user ever touches, and the
 * real path would first run in production.
 *
 * <p>The cost of that choice, stated so it is a decision rather than an oversight: an API
 * returns structured errors and a message id for correlating later webhook events, where
 * SMTP gives a numeric code. And SMTP needs outbound 587, which some networks block where
 * 443 always works. If either becomes a problem, a second {@link Mailer} implementation is
 * the answer -- that is what the interface is for.
 *
 * <p>What neither transport can tell you is whether the message arrived. A {@code 250} here
 * means the relay accepted it for queueing; bounces and spam filtering surface later,
 * through the provider's webhooks or not at all. This throws on the handoff failing and
 * makes no claim beyond that.
 */
@Component
@ConditionalOnProperty(name = "exchange.mail.mode", havingValue = "smtp")
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, @Value("${exchange.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    /**
     * @throws MailException the relay refused the message or could not be reached. Left to
     *         propagate on purpose: the caller decides what a failed send means, and for
     *         registration it means rolling the account back rather than stranding someone
     *         with an address they can never verify and cannot re-register.
     */
    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        sender.send(message);
        // The address is deliberately absent: this line exists to show mail is flowing,
        // and a log of who received what is a record of who holds an account here.
        log.info("sent \"{}\" over SMTP", subject);
    }
}
