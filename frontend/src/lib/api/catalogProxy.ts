const privateHeaders = { "Cache-Control": "private, no-store, max-age=0", "X-Robots-Tag": "noindex, nofollow, noarchive" };

export async function proxyCatalog(request: Request, segments: string[], backendUrl: string | undefined): Promise<Response> {
  if (!backendUrl) return Response.json({ detail: "Live catalog is not configured" }, { status: 503, headers: privateHeaders });
  if (segments[0] !== "courses" || segments.length > 2 ||
      (segments[1] !== undefined && !/^[A-Z]+-\d+(?:-\d+)?$/.test(segments[1]))) {
    return Response.json({ detail: "Not found" }, { status: 404, headers: privateHeaders });
  }
  try {
    const base = new URL(backendUrl);
    if (!["http:", "https:"].includes(base.protocol) || base.username || base.password) throw new Error("Invalid backend URL");
    const url = new URL(`/api/${segments.map(encodeURIComponent).join("/")}`, base);
    const incoming = new URL(request.url);
    for (const key of ["term", "q", "subject", "page", "size"]) {
      const value = incoming.searchParams.get(key);
      if (value !== null) url.searchParams.set(key, value);
    }
    const response = await fetch(url, {
      cache: "no-store", redirect: "error", headers: { Accept: "application/json" },
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]),
    });
    if (!response.ok) {
      return Response.json({ detail: response.status === 404 ? "Course not found in this term" : "Catalog unavailable" }, {
        status: response.status >= 400 && response.status < 500 ? response.status : 503, headers: privateHeaders,
      });
    }
    const data: unknown = await response.json();
    return Response.json(data, { headers: privateHeaders });
  } catch {
    // Neither upstream failures nor instructor names belong in public logs or error pages.
    return Response.json({ detail: "Catalog unavailable" }, { status: 503, headers: privateHeaders });
  }
}
