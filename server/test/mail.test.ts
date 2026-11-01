import assert from "node:assert/strict";
import { test } from "node:test";
import nodemailer from "nodemailer";
import { smtpMailer } from "../src/mail.ts";

test("the sign-in email carries the code in its subject and body", async () => {
  const sent: { envelope: { to: string[] }; message: string }[] = [];
  const transport = nodemailer.createTransport({ jsonTransport: true });
  const original = transport.sendMail.bind(transport);
  transport.sendMail = (async (mail: nodemailer.SendMailOptions) => {
    const info = await original(mail);
    sent.push(info as unknown as { envelope: { to: string[] }; message: string });
    return info;
  }) as typeof transport.sendMail;

  await smtpMailer(transport, "VisualLearn <hello@visuallearn.app>").sendSignInCode("ada@example.com", "042917");
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].envelope.to, ["ada@example.com"]);
  const message = JSON.parse(sent[0].message);
  assert.equal(message.subject, "042917 is your VisualLearn code");
  assert.match(message.text, /042917/);
});
