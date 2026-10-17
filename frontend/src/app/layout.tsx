import type { Metadata } from "next";
import { Archivo, Public_Sans } from "next/font/google";

import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { CatalogProvider } from "@/lib/catalog";
import { StoreProvider } from "@/lib/store";

import "./globals.css";

// Display: Archivo, condensed via its width axis, like a printed timetable.
const display = Archivo({ subsets: ["latin"], axes: ["wdth"], variable: "--font-display" });
// Body: Public Sans, plain and institutional, with clear unslashed numerals for codes and times.
const body = Public_Sans({ subsets: ["latin"], variable: "--font-body" });

export const metadata: Metadata = {
  title: "Syllo course planner",
  description: "Plan next semester and your degree, then send it to your advisor. Student hackathon prototype with demo data.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${display.variable} ${body.variable} h-full antialiased`}>
      <body className="min-h-full bg-paper text-ink">
        <TooltipProvider delayDuration={200}>
          <StoreProvider>
            <CatalogProvider>{children}</CatalogProvider>
          </StoreProvider>
        </TooltipProvider>
        <Toaster theme="light" position="bottom-center" />
      </body>
    </html>
  );
}
