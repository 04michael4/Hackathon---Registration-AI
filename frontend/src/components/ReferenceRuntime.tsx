"use client";

import { useEffect } from "react";
import { CatalogMetadata } from "./CatalogMetadata";

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

export function ReferenceRuntime({ catalogEnabled = false }: Readonly<{ catalogEnabled?: boolean }>) {
  useEffect(() => {
    const marker = document.documentElement.dataset;
    marker.sylloCatalog = catalogEnabled ? "enabled" : "disabled";
    if (marker.sylloRuntime === "loading" || marker.sylloRuntime === "loaded") return;

    marker.sylloRuntime = "loading";
    void runtimeFiles
      .reduce((chain, file) => chain.then(() => loadScript(file)), Promise.resolve())
      .then(() => {
        marker.sylloRuntime = "loaded";
      })
      .catch((error: unknown) => {
        marker.sylloRuntime = "error";
        console.error(error);
      });
  }, [catalogEnabled]);

  return <CatalogMetadata enabled={catalogEnabled} />;
}
