"use client";

import { use } from "react";

import {ChatView}  from "@/components/chat/chat-view";
import { RequireAuth } from "@/components/provider/require-auth";

export default function ChatPage({
  params,
}: {
  params: Promise<{ repoId: string }>;
}) {
  const { repoId } = use(params);

  return (
    <RequireAuth>
      <ChatView repoId={repoId} />
    </RequireAuth>
  );
}