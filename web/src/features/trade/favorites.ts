"use client";

import { useFavoritesOf } from "@/lib/favorites";

/** Starred pairs, kept in this browser only. */
export const useFavorites = () => useFavoritesOf("favorite-pairs");
