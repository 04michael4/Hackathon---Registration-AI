import { isRecord } from "./httpClient";

const privateHeaders = { "Cache-Control": "private, no-store, max-age=0", "X-Robots-Tag": "noindex, nofollow, noarchive" };
function problem(request: Request, status: number, detail: string) {
  return Response.json({ type: "about:blank", title: status === 404 ? "Not Found" : "Service unavailable", status, detail, instance: new URL(request.url).pathname }, { status, headers: { ...privateHeaders, "Content-Type": "application/problem+json" } });
}
/** Fixed route allowlist; this is never an arbitrary URL proxy. No cookies, credentials or secrets forwarded. */
export async function proxyBackend(request: Request, segments: string[], backendUrl: string | undefined): Promise<Response> {
  const route = segments.join("/");
  const get = request.method === "GET" && (route === "terms" || route === "courses" || /^courses\/[A-Z]+-\d+(?:-\d+)?$/.test(route));
  const post = request.method === "POST" && ["schedules/generate", "schedules/check", "schedules/suggest-electives", "schedules/explain", "preferences/extract"].includes(route);
  if (!get && !post) return problem(request, 404, "Not found");
  if (!backendUrl) return problem(request, 503, "Backend is not configured. Set BACKEND_URL and restart the frontend.");
  try {
    const base = new URL(backendUrl);
    if (!["http:", "https:"].includes(base.protocol) || base.username || base.password || base.search || base.hash) return problem(request, 503, "Backend URL configuration is invalid.");
    const url = new URL(`${base.href.replace(/\/$/, "")}/api/${route}`);
    const incoming = new URL(request.url);
    for (const key of ["term", "q", "subject", "page", "size"]) {
      const value = incoming.searchParams.get(key);
      if (get && value !== null) url.searchParams.set(key, value);
    }
    let body: string | undefined;
    if (post) {
      if (!request.headers.get("content-type")?.includes("application/json")) return problem(request, 415, "Send JSON request data.");
      body = await request.text();
      if (new TextEncoder().encode(body).length > 65_536) return problem(request, 413, "The request is too large.");
      try { JSON.parse(body); } catch { return problem(request, 400, "The request must contain valid JSON."); }
    }
    const response = await fetch(url, { method: request.method, body, cache: "no-store", redirect: "error", headers: { Accept: "application/json", ...(post ? { "Content-Type": "application/json" } : {}) }, signal: AbortSignal.any([request.signal, AbortSignal.timeout(30_000)]) });
    const headers: Record<string, string> = { ...privateHeaders };
    const requestId = response.headers.get("X-Request-Id");
    if (requestId) headers["X-Request-Id"] = requestId;
    if (response.status === 204) return new Response(null, { status: 204, headers });
    const data: unknown = await response.json();
    if (!response.ok) {
      if (!isRecord(data) || typeof data.detail !== "string") return problem(request, 502, "The backend returned an unexpected error response.");
      headers["Content-Type"] = "application/problem+json";
      return Response.json(response.status >= 500 && response.status !== 503 ? { type: "about:blank", title: "Backend error", status: response.status, detail: "The backend could not complete this request. Please retry.", ...(requestId ? { requestId } : {}) } : data, { status: response.status, headers });
    }
    return Response.json(data, { status: response.status, headers });
  } catch (error) {
    // Do not log bodies or upstream details: catalog data can contain real names.
    if (error instanceof SyntaxError) return problem(request, 502, "The backend returned invalid JSON. Please retry.");
    return problem(request, error instanceof Error && error.name === "TimeoutError" ? 504 : 503, "Cannot reach the backend. Check BACKEND_URL and that Spring Boot is running, then retry.");
  }
}
