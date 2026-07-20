import { describe, expect, it, vi } from 'vitest'
import { fetchBackend } from './backendTransport'

describe('fetchBackend', () => {
  it('returns a typed error when the backend cannot be reached', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'))

    await expect(fetchBackend(fetcher, 'https://backend.invalid/api/portfolio/summary')).resolves.toEqual({
      ok: false,
      error: 'Backend unavailable. Check your connection and try again.'
    })
  })
})
