/**
 * A webhook's sender for the e2e runs (W6): posts a delivery to `/api/hooks/{systemId}` signed as Standard Webhooks
 * sign (`v1,` and the base64 HMAC-SHA256 of `{id}.{timestamp}.{body}`, keyed with the secret decoded after
 * `whsec_`), or with the secret in a header. TEST FIXTURE ONLY.
 */
import { createHmac, randomUUID } from 'node:crypto';

export interface WebhookAnswer {
  status: number;
  body: unknown;
}

export async function deliverWebhook(
  serverUrl: string,
  systemId: string,
  secret: string,
  payload: Record<string, unknown>,
  options: { header?: string } = {},
): Promise<WebhookAnswer> {
  const body = JSON.stringify(payload);
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (options.header) {
    headers[options.header] = secret;
  } else {
    const id = `msg_${randomUUID()}`;
    const timestamp = String(Math.floor(Date.now() / 1000));
    const key = Buffer.from(secret.replace(/^whsec_/, ''), 'base64');
    const signature = createHmac('sha256', key)
      .update(`${id}.${timestamp}.${body}`)
      .digest('base64');
    headers['webhook-id'] = id;
    headers['webhook-timestamp'] = timestamp;
    headers['webhook-signature'] = `v1,${signature}`;
  }
  const response = await fetch(`${serverUrl}/api/hooks/${systemId}`, {
    method: 'POST',
    headers,
    body,
  });
  const text = await response.text();
  let parsed: unknown = text;
  try {
    parsed = JSON.parse(text);
  } catch {
    // not JSON
  }
  return { status: response.status, body: parsed };
}
