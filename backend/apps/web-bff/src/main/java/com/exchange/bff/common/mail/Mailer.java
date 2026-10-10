package com.exchange.bff.common.mail;

/**
 * Sending an email, as the rest of this app sees it.
 *
 * <p>A port, so nothing that needs to send mail has to know whether that means SMTP, SES,
 * or a line in a log. The alternative -- reaching for {@code JavaMailSender} directly --
 * makes every test that touches registration need a mail server, or a mock of one, at
 * every call site.
 *
 * <p>A dumb pipe on purpose: no templates, no typed message kinds. The caller knows what
 * it is saying and to whom; this only knows how to deliver. When there are enough messages
 * that composing them in each service starts to hurt, templates belong behind their own
 * type rather than inside this one.
 */
public interface Mailer {

    void send(String to, String subject, String body);
}
