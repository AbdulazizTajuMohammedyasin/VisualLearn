import nodemailer, { type Transporter } from "nodemailer";
import type { Mailer } from "./auth.ts";

/**
 * Sends sign-in codes over SMTP. Works with any transactional provider that offers SMTP
 * (Amazon SES, Postmark, Resend, Mailgun); [url] looks like smtps://user:pass@smtp.example.com:465.
 */
export function smtpMailer(url: string | Transporter, from: string): Mailer {
  const transport = typeof url === "string" ? nodemailer.createTransport(url) : url;
  return {
    async sendSignInCode(email, code) {
      await transport.sendMail({
        from,
        to: email,
        subject: `${code} is your VisualLearn code`,
        text: `Your VisualLearn sign-in code is ${code}.\n\nIt works for 10 minutes. If you didn't ask for it, you can ignore this email.`,
        html: `<p>Your VisualLearn sign-in code is</p><p style="font-size:28px;font-weight:600;letter-spacing:4px">${code}</p><p>It works for 10 minutes. If you didn't ask for it, you can ignore this email.</p>`,
      });
    },
  };
}
