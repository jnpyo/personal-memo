export type WorkspaceView = 'GRAPH' | 'MEMOS' | 'AGENDA' | 'SETTINGS';

type Props = {
  activeView: WorkspaceView;
  onSelect: (view: WorkspaceView) => void;
};

const NAVIGATION_ITEMS: ReadonlyArray<{ view: WorkspaceView; label: string }> = [
  { view: 'GRAPH', label: '연결' },
  { view: 'MEMOS', label: '추가' },
  { view: 'AGENDA', label: '일정' },
  { view: 'SETTINGS', label: '설정' },
];

function NavigationIcon({ view }: { view: WorkspaceView }) {
  const commonProps = {
    className: 'workspace-navigation__icon',
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    focusable: false,
  };

  switch (view) {
    case 'GRAPH':
      return (
        <svg {...commonProps}>
          <path d="M7.2 7.8 10 10.4M14 10.4l2.8-2.6M7.5 16.1l2.7-2.6M13.8 13.5l2.7 2.6" />
          <circle cx="5.5" cy="6.2" r="2.2" />
          <circle cx="18.5" cy="6.2" r="2.2" />
          <circle cx="12" cy="12" r="2.5" />
          <circle cx="5.5" cy="17.8" r="2.2" />
          <circle cx="18.5" cy="17.8" r="2.2" />
        </svg>
      );
    case 'MEMOS':
      return (
        <svg {...commonProps}>
          <path d="M6.5 3.8h8.2l2.8 2.8v13.6h-11z" />
          <path d="M14.7 3.8v3h2.8M9 11h6M9 14.5h6M9 18h4" />
        </svg>
      );
    case 'AGENDA':
      return (
        <svg {...commonProps}>
          <rect x="4" y="5.5" width="16" height="14.5" rx="2.2" />
          <path d="M8 3.5v4M16 3.5v4M4 9.5h16M8 13h2M14 13h2M8 16.5h2" />
        </svg>
      );
    case 'SETTINGS':
      return (
        <svg {...commonProps}>
          <path d="M5 6h14M5 12h14M5 18h14" />
          <circle cx="9" cy="6" r="2" />
          <circle cx="15" cy="12" r="2" />
          <circle cx="10" cy="18" r="2" />
        </svg>
      );
  }
}

export function WorkspaceNavigation({ activeView, onSelect }: Props) {
  return (
    <nav className="workspace-navigation" aria-label="주요 화면">
      {NAVIGATION_ITEMS.map(({ view, label }) => (
        <button
          key={view}
          type="button"
          className="workspace-navigation__item"
          aria-current={activeView === view ? 'page' : undefined}
          onClick={() => onSelect(view)}
        >
          <NavigationIcon view={view} />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}
