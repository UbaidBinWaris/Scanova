import type { ReactNode, SVGProps } from 'react';

/** Inline stroke icons (24×24, currentColor). Decorative unless given a title. */
function Icon({
  children,
  ...props
}: SVGProps<SVGSVGElement> & { children: ReactNode }): ReactNode {
  return (
    <svg
      viewBox="0 0 24 24"
      width={20}
      height={20}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  );
}

type IconProps = SVGProps<SVGSVGElement>;

export const DocumentIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
    <path d="M14 3v5h5M9 13h6M9 17h4" />
  </Icon>
);
export const SearchIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Icon>
);
export const DownloadIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19h14" />
  </Icon>
);
export const TrashIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
  </Icon>
);
export const PencilIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z" />
    <path d="m13.5 6.5 4 4" />
  </Icon>
);
export const TagIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z" />
    <circle cx="7.5" cy="7.5" r="1.5" />
  </Icon>
);
export const UserIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21a8 8 0 0 1 16 0" />
  </Icon>
);
export const UsersIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6 6 0 0 1 3.5 6" />
  </Icon>
);
export const KeyIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="8" cy="15" r="4" />
    <path d="m11 12 9-9M17 6l3 3M15 8l2 2" />
  </Icon>
);
export const ListIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" />
  </Icon>
);
export const LogOutIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H3" />
  </Icon>
);
export const ChevronLeftIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="m15 18-6-6 6-6" />
  </Icon>
);
export const ChevronRightIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="m9 18 6-6-6-6" />
  </Icon>
);
export const XIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Icon>
);
export const CheckIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="m5 12 5 5L20 7" />
  </Icon>
);
export const CopyIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <rect x="9" y="9" width="11" height="11" rx="2" />
    <path d="M5 15V5a2 2 0 0 1 2-2h8" />
  </Icon>
);
export const PlusIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);
export const RefreshIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M20 11a8 8 0 0 0-14.5-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.5 4.5L20 16M20 20v-4h-4" />
  </Icon>
);
export const ShieldIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M12 3 5 6v6c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V6z" />
  </Icon>
);
export const AlertIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M12 9v4M12 17h.01" />
    <path d="M10.3 3.9 2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0" />
  </Icon>
);
export const ImageIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <circle cx="9" cy="10" r="2" />
    <path d="m21 16-5-5-9 9" />
  </Icon>
);
export const ZoomInIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5M11 8v6M8 11h6" />
  </Icon>
);
export const ZoomOutIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5M8 11h6" />
  </Icon>
);
export const TextIcon = (props: IconProps): ReactNode => (
  <Icon {...props}>
    <path d="M4 6h16M4 12h16M4 18h10" />
  </Icon>
);

/** The Scanova mark: the download site's logo in the brand emerald. */
export function BrandMark({ className }: { className?: string }): ReactNode {
  return (
    <svg viewBox="0 0 32 32" width={28} height={28} aria-hidden="true" className={className}>
      <rect x="3" y="3" width="26" height="26" rx="7" className="fill-primary" />
      <path
        d="M10 11h12M10 16h12M10 21h7"
        className="stroke-primary-foreground"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
    </svg>
  );
}
