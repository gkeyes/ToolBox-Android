import React from "react";
import { createPortal } from "react-dom";
import { Button } from "@heroui/react";
import { authState } from "@/stores/authStore.js";
import { feeds } from "@/stores/feedsStore.js";
import { SERVER_URL } from "@/toolbox/network.js";
import { createFeedTitleFilterControl } from "@/toolbox/title-filter/title-filter-control.mjs";
import "@/toolbox/title-filter/title-filter.css";

// Reuse the host reader's real components, auth and feed store; load the modal on demand.
export default createFeedTitleFilterControl({
  React, createPortal, Button, authState, feeds, serverUrl: SERVER_URL,
  loadModal: () => import("@/components/ui/CustomModal.jsx").then(module => module.default),
});
