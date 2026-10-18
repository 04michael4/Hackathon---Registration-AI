"use client";

import { useEffect } from "react";

const runtimeFiles = [
  "data.js",
  "degree-ai.js",
  "semester.js",
  "degree.js",
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

export function ReferenceRuntime() {
  useEffect(() => {
    const marker = document.documentElement.dataset;
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
  }, []);

  return null;
}
