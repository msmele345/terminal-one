export type BackendTransportResult =
  | { ok: true; response: Response }
  | { ok: false; error: string }

export async function fetchBackend(
  fetcher: typeof fetch,
  input: string | URL | Request,
  init?: RequestInit
): Promise<BackendTransportResult> {
  try {
    return { ok: true, response: await fetcher(input, init) }
  } catch {
    return {
      ok: false,
      error: 'Backend unavailable. Check your connection and try again.'
    }
  }
}
