import type { Metadata } from "next";
import "./globals.css";
import "../styles/catalog-metadata.css";

export const metadata: Metadata = {
  title: "Syllo — Course Planning",
  description: "Syllo — a simple course planning assistant for Lehigh students.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Manrope:wght@500;600;700&display=swap"
          rel="stylesheet"
        />
      </head>
      <body className="welcome-only">{children}</body>
    </html>
  );
}
