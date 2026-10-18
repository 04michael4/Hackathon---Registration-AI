import { proxyBackend } from "./backendProxy";
/** Compatibility route for the explicitly enabled prototype metadata browser. */
export function proxyCatalog(request: Request, segments: string[], backendUrl: string | undefined): Promise<Response> {
  if (segments[0] !== "courses") return Promise.resolve(Response.json({ detail: "Not found" }, { status: 404 }));
  return proxyBackend(request, segments, backendUrl);
}
