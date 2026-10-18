"use client";

import { useEffect, useState } from "react";
import { CatalogMetadata } from "./CatalogMetadata";
import { ConnectedPlanner } from "./ConnectedPlanner";

const runtimeFiles = [
  "data.js",
  "degree-ai.js",
  "planner-logic.js",
  "semester.js",
  "degree.js",
  "ai-widget.js",
  "app.js",
  "polish.js",
] as const;

function loadScript(file: (typeof runtimeFiles)[number]) {
  return new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = `/reference/${file}`;
    script.async = false;
    script.dataset.sylloReference = file;
    script.addEventListener("load", () => resolve(), { once: true });
    script.addEventListener("error", () => reject(new Error(`Failed to load ${file}`)), {
      once: true,
    });
    document.body.append(script);
  });
}

export function ReferenceRuntime({ catalogEnabled = false, demo = false }: Readonly<{ catalogEnabled?: boolean; demo?: boolean }>) {
  const [hosts, setHosts] = useState<{ planner: Element; saved: Element | null } | null>(null);
  useEffect(() => {
    const marker = document.documentElement.dataset;
    marker.sylloMode = demo ? "demo" : "api";
    marker.sylloCatalog = catalogEnabled ? "enabled" : "disabled";
    const mount = () => { const planner = document.getElementById("api-planner-root"); if (planner && !demo) setHosts({ planner, saved: document.getElementById("saved-list") }); };
    if (marker.sylloRuntime === "loaded") { mount(); return; }
    if (marker.sylloRuntime === "loading") { document.addEventListener("syllo:runtime-ready", mount, { once: true }); return () => document.removeEventListener("syllo:runtime-ready", mount); }

    marker.sylloRuntime = "loading";
    void runtimeFiles.filter(file => demo || file !== "semester.js")
      .reduce((chain, file) => chain.then(() => loadScript(file)), Promise.resolve())
      .then(() => {
        marker.sylloRuntime = "loaded";
        document.dispatchEvent(new Event("syllo:runtime-ready"));
        mount();
      })
      .catch((error: unknown) => {
        marker.sylloRuntime = "error";
        console.error(error);
      });
  }, [catalogEnabled, demo]);

  return <>{demo && <CatalogMetadata enabled={catalogEnabled} />}{hosts && <ConnectedPlanner host={hosts.planner} savedHost={hosts.saved} />}</>;
}
