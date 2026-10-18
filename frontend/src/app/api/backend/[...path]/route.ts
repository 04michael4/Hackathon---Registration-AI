import { proxyBackend } from "@/lib/api/backendProxy";
export const dynamic = "force-dynamic";
async function handle(request: Request, context: { params: Promise<{ path: string[] }> }) {
  return proxyBackend(request, (await context.params).path, process.env.BACKEND_URL);
}
export const GET = handle;
export const POST = handle;
