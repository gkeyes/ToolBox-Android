// Keep the sidebar preference bootstrap separate from the existing controls.
if (new URLSearchParams(location.search).get("sidebar") === "1") {
  await import("./controls-sidebar.jsx");
} else {
  await import("./controls-main.jsx");
}
