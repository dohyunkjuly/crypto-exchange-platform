"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

/** The first operated screen. Nothing lives at the root: what an admin opens is a section. */
export default function HomePage() {
  const router = useRouter();
  useEffect(() => router.replace("/dashboard"), [router]);
  return null;
}
