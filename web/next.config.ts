import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Traces each page's actual dependencies and copies only those into .next/standalone,
  // so the production image doesn't need node_modules installed at all -- see Dockerfile.
  output: "standalone",
};

export default nextConfig;
