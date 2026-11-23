import { useQuery } from '@tanstack/react-query'
import type { Account, Event, Inventory } from './model'

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly details: Record<string, unknown>) {
    super(message)
  }
}

type Csrf = { headerName: string; token: string }
let csrf: Promise<Csrf> | undefined
const prefix = '/api/v1'

async function csrfToken() {
  csrf ??= fetch(prefix + '/auth/csrf', { cache: 'no-store' })
    .then(async response => {
      if (!response.ok) throw new Error('Could not establish a secure session.')
      return response.json() as Promise<Csrf>
    })
    .catch((error: unknown) => {
      csrf = undefined
      throw error
    })
  return csrf
}

export async function api<T>(path: string, init?: RequestInit, retried = false): Promise<T> {
  const method = init?.method ?? 'GET'
  const changes = method !== 'GET' && method !== 'HEAD'
  const headers = new Headers(init?.headers)
  headers.set('Accept', 'application/json')
  if (init?.body) headers.set('Content-Type', 'application/json')
  if (changes) {
    const { headerName, token } = await csrfToken()
    headers.set(headerName, token)
  }
  let response: Response
  try {
    response = await fetch(prefix + path, { ...init, headers, cache: 'no-store' })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new Error('SeatLock could not connect. Check your connection and try again.')
  }
  if (changes && response.status === 403 && !retried) {
    csrf = undefined
    return api<T>(path, init, true)
  }
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED',
      error?.message ?? 'SeatLock could not complete the request. Try again shortly.', error?.details ?? {})
  }
  if (changes && path.startsWith('/auth/')) csrf = undefined
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function send<T>(path: string, body?: unknown, method = 'POST', key?: string) {
  return api<T>(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : undefined,
  })
}

export const accountKey = ['account'] as const
export function useAccount() {
  return useQuery({
    queryKey: accountKey,
    queryFn: async ({ signal }) => {
      try {
        return await api<Account>('/me', { signal })
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
    staleTime: 30_000,
    retry: false,
  })
}

export function useCapabilities() {
  return useQuery({
    queryKey: ['capabilities'], queryFn: () => api<{ demo: boolean }>('/auth/capabilities'), staleTime: 60_000,
  })
}

export const eventPath = (id: string) => '/events/' + encodeURIComponent(id)
export const inventoryKey = (id: string) => ['inventory', id] as const
export const inventory = (id: string, signal?: AbortSignal) => api<Inventory>(eventPath(id) + '/seats', { signal })

export function useEvent(id: string) {
  return useQuery({ queryKey: ['event', id], queryFn: ({ signal }) => api<Event>(eventPath(id), { signal }), enabled: !!id })
}
