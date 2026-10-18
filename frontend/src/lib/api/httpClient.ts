import type { FieldError, ProblemDetails } from "./types";

export class ApiError extends Error {
  constructor(message: string, readonly status = 0, readonly errors: FieldError[] = [], readonly requestId?: string, readonly kind: "http" | "network" | "timeout" | "invalid" = "http") { super(message); this.name = "ApiError"; }
}
export const isRecord = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value);
const defaults: Record<number, string> = { 400: "Check the values in your request.", 401: "Your session has expired. Sign in again.", 403: "This request is not permitted.", 404: "The requested resource was not found.", 409: "The request conflicts with the current state.", 422: "Some selected courses are not available for this term.", 429: "Too many requests. Please try again shortly.", 500: "The backend could not complete this request. Please retry.", 502: "The backend returned an unexpected response.", 503: "The backend is unavailable. Check that it is running and retry.", 504: "The backend took too long to respond. Please retry." };

/** Same-origin requests. BACKEND_URL stays server-only; no credentials or catalog bodies are logged. */
export async function requestJson<T>(path: string, options: { body?: unknown; signal?: AbortSignal; timeoutMs?: number } = {}, validate?: (value: unknown) => boolean): Promise<T> {
  let response: Response;
  const timeout = AbortSignal.timeout(options.timeoutMs ?? 35_000);
  try {
    response = await fetch(path, {
      method: options.body === undefined ? "GET" : "POST", cache: "no-store", credentials: "omit",
      headers: { Accept: "application/json", ...(options.body === undefined ? {} : { "Content-Type": "application/json" }) },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout,
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    if (timeout.aborted) throw new ApiError(defaults[504], 0, [], undefined, "timeout");
    throw new ApiError("Cannot reach the backend. Check your connection and retry.", 0, [], undefined, "network");
  }
  if (response.status === 204) return undefined as T;
  let data: unknown;
  try { data = await response.json(); } catch { throw new ApiError(response.ok ? "The backend returned invalid JSON. Please retry." : (defaults[response.status] ?? "The request failed. Please retry."), response.status, [], response.headers.get("X-Request-Id") ?? undefined, "invalid"); }
  if (!response.ok) {
    const problem: Partial<ProblemDetails> = isRecord(data) ? data : {};
    const errors = Array.isArray(problem.errors) ? problem.errors.filter((item): item is FieldError => isRecord(item) && typeof item.field === "string" && typeof item.message === "string") : [];
    // Backend RFC 9457 details are sanitized. Hide unexpected server/provider errors even if a proxy misbehaves.
    const detail = response.status < 500 || response.status === 503 ? problem.detail : undefined;
    throw new ApiError(typeof detail === "string" && detail.length < 2000 ? detail : (defaults[response.status] ?? "The request failed. Please retry."), response.status, errors, typeof problem.requestId === "string" ? problem.requestId : response.headers.get("X-Request-Id") ?? undefined);
  }
  if (validate && !validate(data)) throw new ApiError("The backend returned an unexpected response. Please retry.", response.status, [], undefined, "invalid");
  return data as T;
}
