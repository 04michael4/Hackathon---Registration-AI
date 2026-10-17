import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Syllo — Course Planning",
  description: "A browser-only course planning prototype for Lehigh students.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
