"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";


import { api, ApiError } from "@/lib/api";
const authQueryKeys = {
  all: ["auth"] as const,
  me: () => ["auth", "me"] as const,
};

export const AUTH_COOKIE = "devpilot_auth";

export function setAuthCookie(authed: boolean) {
  if (typeof document === "undefined") return;
  if (authed) {
    document.cookie = `${AUTH_COOKIE}=1; path=/; max-age=${60 * 60 * 24 * 7}; SameSite=Lax`;
  } else {
    document.cookie = `${AUTH_COOKIE}=; path=/; max-age=0; SameSite=Lax`;
  }
}


export function useCurrentUser(){
    return useQuery({
        queryKey: authQueryKeys.me(),
        queryFn: async()=>{
            try {
                const user = await api.me();
                setAuthCookie(true);
                return user;
            } catch (error) {
                setAuthCookie(false);
                throw error;
            }

        },
         staleTime: 5 * 60 * 1000,
    retry: false,
    })
}

export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: () => api.logout(),
    onSettled: async () => {
      setAuthCookie(false);
      queryClient.setQueryData(authQueryKeys.me(), null);
      await queryClient.invalidateQueries({ queryKey: authQueryKeys.all });
      router.replace("/login");
    },
  });
}