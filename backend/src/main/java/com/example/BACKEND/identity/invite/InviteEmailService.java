package com.example.BACKEND.identity.invite;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class InviteEmailService {

    private static final Logger log = LoggerFactory.getLogger(InviteEmailService.class);
    private static final DateTimeFormatter EXPIRY_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    private final Optional<JavaMailSender> mailSender;

    private final boolean mailEnabled;
    private final String fromAddress;
    private final String productName;

    public InviteEmailService(
            Optional<JavaMailSender> mailSender,
            @Value("${kontexa.mail.enabled:false}") boolean mailEnabled,
            @Value("${kontexa.mail.from:invites@kontexa.io}") String fromAddress,
            @Value("${kontexa.mail.product-name:Kontexa}") String productName
    ) {
        this.mailSender = mailSender;
        this.mailEnabled = mailEnabled;
        this.fromAddress = fromAddress;
        this.productName = productName;
    }

    public boolean sendInviteEmail(
            String toEmail,
            String workspaceName,
            String inviterName,
            String activationUrl,
            LocalDateTime expiresAt
    ) {
        String subject = "You've been invited to join Kontexa";
        String html = buildHtml(workspaceName, inviterName, activationUrl, expiresAt);
        String text = buildPlainText(workspaceName, inviterName, activationUrl, expiresAt);

        if (!mailEnabled || mailSender.isEmpty()) {
            log.info("Invite email (SMTP disabled) — to={} workspace={} link={}",
                    toEmail, workspaceName, activationUrl);
            return false;
        }

        try {
            MimeMessage message = mailSender.get().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.get().send(message);
            return true;
        } catch (Exception ex) {
            log.error("Failed to send invite email to {} — invite still created. activationUrl={}",
                    toEmail, activationUrl, ex);
            return false;
        }
    }

    private String buildPlainText(
            String workspaceName,
            String inviterName,
            String activationUrl,
            LocalDateTime expiresAt
    ) {
        return """
                You've been invited to join %s on %s.

                %s invited you to collaborate in their workspace.

                Activate your account:
                %s

                This invitation expires on %s (72 hours).

                If you did not expect this invitation, you can safely ignore this email.
                """.formatted(
                workspaceName,
                productName,
                inviterName != null ? inviterName : "A workspace admin",
                activationUrl,
                EXPIRY_FMT.format(expiresAt)
        );
    }

    private String buildHtml(
            String workspaceName,
            String inviterName,
            String activationUrl,
            LocalDateTime expiresAt
    ) {
        String inviter = inviterName != null && !inviterName.isBlank()
                ? inviterName : "A workspace admin";
        String expiry = EXPIRY_FMT.format(expiresAt);

        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#0b0f14;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#0b0f14;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#121820;border:1px solid #1e2a38;border-radius:12px;overflow:hidden;">
                        <tr>
                          <td style="padding:32px 36px 24px;border-bottom:1px solid #1e2a38;">
                            <span style="display:inline-block;width:32px;height:32px;background:#3b82f6;border-radius:8px;color:#fff;font-weight:700;line-height:32px;text-align:center;font-size:16px;">K</span>
                            <span style="margin-left:10px;color:#e8edf4;font-size:13px;letter-spacing:0.12em;font-weight:600;">%s</span>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:36px;">
                            <h1 style="margin:0 0 12px;color:#f1f5f9;font-size:22px;font-weight:600;line-height:1.3;">
                              You've been invited to join Kontexa
                            </h1>
                            <p style="margin:0 0 24px;color:#94a3b8;font-size:15px;line-height:1.6;">
                              <strong style="color:#cbd5e1;">%s</strong> invited you to join
                              <strong style="color:#cbd5e1;">%s</strong> on %s.
                            </p>
                            <table cellpadding="0" cellspacing="0" style="margin:0 0 28px;">
                              <tr>
                                <td style="background:#2563eb;border-radius:8px;">
                                  <a href="%s" style="display:inline-block;padding:14px 28px;color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;">
                                    Activate your account
                                  </a>
                                </td>
                              </tr>
                            </table>
                            <p style="margin:0 0 8px;color:#64748b;font-size:13px;line-height:1.5;">
                              This invitation expires on <strong style="color:#94a3b8;">%s</strong>.
                            </p>
                            <p style="margin:0;color:#475569;font-size:12px;line-height:1.5;">
                              If the button doesn't work, copy this link into your browser:<br>
                              <a href="%s" style="color:#60a5fa;word-break:break-all;">%s</a>
                            </p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:20px 36px;background:#0d1219;border-top:1px solid #1e2a38;">
                            <p style="margin:0;color:#475569;font-size:11px;line-height:1.5;">
                              Enterprise workspace provisioning — invite-only access. If you did not expect this email, ignore it.
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(
                productName.toUpperCase(),
                inviter,
                workspaceName,
                productName,
                activationUrl,
                expiry,
                activationUrl,
                activationUrl
        );
    }
}
