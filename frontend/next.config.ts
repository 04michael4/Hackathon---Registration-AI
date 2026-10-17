import type { NextConfig } from "next";

// The browser only ever calls same-origin /api/...; Next forwards it to the Spring Boot backend.
// No CORS setup is needed, and the backend URL never reaches client code.
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

export default nextConfig;
