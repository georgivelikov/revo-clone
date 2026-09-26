import { env } from '../config/env'

export class ApiError extends Error {
  readonly status: number
  readonly detail?: string

  constructor(status: number, message: string, detail?: string) {
    super(message)
    this.status = status
    this.detail = detail
  }
}

export async function apiFetch<T>(
  path: string,
  accessToken: string,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    ...init,
    headers: {
      ...init?.headers,
      Authorization: `Bearer ${accessToken}`,
      Accept: 'application/json',
    },
  })

  if (!response.ok) {
    throw await toApiError(path, response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

export function apiPost<T>(
  path: string,
  accessToken: string,
  body: unknown,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  return apiFetch<T>(path, accessToken, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
    body: JSON.stringify(body),
  })
}

// Every service's ApiExceptionHandler (common-web) and the gateway's own idempotency filter
// (services/api-gateway ProblemResponse) answer errors as RFC-7807 application/problem+json, so
// `detail` is preferred over the generic status-line message whenever the body parses.
async function toApiError(path: string, response: Response): Promise<ApiError> {
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('json')) {
    try {
      const body = (await response.json()) as { title?: string; detail?: string }
      const message = body.detail ?? body.title ?? `${path} failed with status ${response.status}`
      return new ApiError(response.status, message, body.detail)
    } catch {
      // Body claimed to be JSON but was not - fall through to the generic error below.
    }
  }
  return new ApiError(response.status, `${path} failed with status ${response.status}`)
}
