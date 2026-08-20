/**
 * Demo configuration.
 *
 * Everything except which transport is in use lives in the backend's .env — all three
 * clients talk to the same backend, so one edit takes effect everywhere and nobody has
 * to go hunting through files. This module only reads it out and writes it back.
 */
import { t } from './i18n'

/**
 * The backend address this page talks to. Takes the current page's hostname with a
 * fixed port of 8787 — the backend is on whichever machine served the page.
 */
export const BACKEND_URL = `${location.protocol}//${location.hostname}:8787`

/** The entries in the backend's .env that a client may edit. Key names match
 *  EDITABLE_KEYS in server.py. */
export type BackendConfig = Record<string, string>

export async function fetchBackendConfig(): Promise<BackendConfig> {
  const res = await fetch(`${BACKEND_URL}/api/config`)
  if (!res.ok) throw new Error(t.value.configReadFailed(res.status))
  return (await res.json()) as BackendConfig
}

export async function saveBackendConfig(config: BackendConfig): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
  if (!res.ok) throw new Error(t.value.configSaveFailed(res.status))
}
