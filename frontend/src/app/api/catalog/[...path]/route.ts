import { proxyCatalog } from "@/lib/api/catalogProxy";

export const dynamic = "force-dynamic";

export async function GET(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxyCatalog(request, path, process.env.BACKEND_URL);
}
