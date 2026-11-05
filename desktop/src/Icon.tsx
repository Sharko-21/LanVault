import type { ReactNode } from "react";
type IconName =
  | "archive"
  | "devices"
  | "settings"
  | "folder"
  | "arrow"
  | "check"
  | "pause"
  | "play"
  | "close";
const paths: Record<IconName, ReactNode> = {
  archive: (
    <>
      <path d="M4 8h16v12H4zM3 4h18v4H3zM9 12h6" />
    </>
  ),
  devices: (
    <>
      <rect x="3" y="3" width="12" height="14" rx="2" />
      <path d="M7 21h5m-2-4v4" />
      <rect x="16" y="8" width="5" height="13" rx="1.5" />
    </>
  ),
  settings: (
    <>
      <path d="M4 6h16M4 12h16M4 18h16" />
      <path d="M8 3v6m8 0v6m-6 0v6" />
    </>
  ),
  folder: <path d="M3 7V5h6l2 2h10v13H3V7z" />,
  arrow: <path d="M5 12h14m-6-6 6 6-6 6" />,
  check: <path d="m5 12 4 4L19 6" />,
  pause: <path d="M8 5v14M16 5v14" />,
  play: <path d="m8 5 11 7-11 7z" />,
  close: <path d="m6 6 12 12M6 18 18 6" />,
};
export function Icon({ name }: { name: IconName }) {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}
