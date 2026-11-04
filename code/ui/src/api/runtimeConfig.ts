export const CALLBACK_PATH = '/auth/callback'

export function isCallbackPath(path: string): boolean {
  return path.replace(/\/$/, '') === CALLBACK_PATH
}

// The exact URI registered at TARA comes from the deployment (nginx /runtime-config.json, env
// TARA_REDIRECT_URI). Without it the callback route on the current origin is used, with the
// trailing slash RIA registers (TARA matches the URI exactly).
export async function getTaraRedirectUri(): Promise<string> {
  try {
    const res = await fetch('/runtime-config.json')
    const uri = res.ok ? (await res.json())?.taraRedirectUri : null
    if (typeof uri === 'string' && uri) return uri
  } catch {}
  return `${location.origin}${CALLBACK_PATH}/`
}
