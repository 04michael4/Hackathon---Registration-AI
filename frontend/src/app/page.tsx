import fs from "node:fs";
import path from "node:path";

import { ReferenceRuntime } from "@/components/ReferenceRuntime";

export const dynamic = "force-dynamic";

const referenceDocument = fs.readFileSync(
  path.join(process.cwd(), "src", "reference", "index.html"),
  "utf8",
);
const referenceBodyMatch = referenceDocument.match(/<body\b[^>]*>([\s\S]*?)<\/body>/i);

if (!referenceBodyMatch) {
  throw new Error("The authoritative Syllo reference body could not be loaded.");
}

const referenceBody = referenceBodyMatch[1].replace(
  /\s*<script\b[^>]*><\/script>/gi,
  "",
);

export default function Home() {
  const demo = process.env.FRONTEND_MODE === "demo";
  const body = demo ? referenceBody : referenceBody.replace('<div class="planner-layout ai-closed">', '<div class="planner-layout ai-closed"><div id="api-planner-root"></div>').replace(/<div class="sem-registration">[\s\S]*?<\/div>/, '<div class="sem-registration" id="api-term-header"></div>').replace("Sign in with Lehigh SSO", "Continue to course planner").replace("Demo entry · SSO is not connected", "No account required · SSO is not connected");
  return (
    <>
      <div id="syllo-reference-root" data-mode={demo ? "demo" : "api"} dangerouslySetInnerHTML={{ __html: body }} />
      <ReferenceRuntime demo={demo} catalogEnabled={Boolean(process.env.BACKEND_URL)} />
    </>
  );
}
