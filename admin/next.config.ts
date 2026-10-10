import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Traces each page's actual dependencies into .next/standalone, so the image needs no
  // node_modules -- see Dockerfile.
  output: "standalone",
};

export default nextConfig;
