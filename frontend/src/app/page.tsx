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
  return (
    <>
      <div id="syllo-reference-root" dangerouslySetInnerHTML={{ __html: referenceBody }} />
      <ReferenceRuntime catalogEnabled={Boolean(process.env.BACKEND_URL)} />
    </>
  );
}
