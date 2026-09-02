import { Children, isValidElement, type ReactElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { WorkspaceNavigation, type WorkspaceView } from './WorkspaceNavigation';

type NavigationButtonProps = {
  children: ReactNode;
  onClick: () => void;
};

describe('workspace navigation', () => {
  it('renders four native, labelled view buttons with one current page', () => {
    const markup = renderToStaticMarkup(
      <WorkspaceNavigation activeView="AGENDA" onSelect={vi.fn()} />,
    );

    expect(markup).toContain('<nav class="workspace-navigation" aria-label="주요 화면">');
    expect(markup.match(/<button/g)).toHaveLength(4);
    expect(markup.match(/class="workspace-navigation__item"/g)).toHaveLength(4);
    expect(markup.match(/<svg/g)).toHaveLength(4);
    expect(markup.match(/aria-hidden="true"/g)).toHaveLength(4);
    expect(markup.match(/aria-current="page"/g)).toHaveLength(1);
    for (const label of ['연결', '추가', '일정', '설정']) {
      expect(markup).toContain(`<span>${label}</span>`);
    }
  });

  it('reports the selected view for every button', () => {
    const onSelect = vi.fn();
    const navigation = WorkspaceNavigation({ activeView: 'GRAPH', onSelect });
    const buttons = Children.toArray(
      (navigation.props as { children: ReactNode }).children,
    ).filter(
      (child): child is ReactElement<NavigationButtonProps> =>
        isValidElement<NavigationButtonProps>(child) && child.type === 'button',
    );

    for (const button of buttons) button.props.onClick();

    expect(onSelect.mock.calls).toEqual(
      (['GRAPH', 'MEMOS', 'AGENDA', 'SETTINGS'] satisfies WorkspaceView[]).map((view) => [view]),
    );
  });
});
