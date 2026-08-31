import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AuthSession } from '../../shared/api/types';
import { AccountPanel } from './AccountPanel';

const baseSession: AuthSession = {
  userId: '0711213d-a079-4dc1-92af-f123ba60e45a',
  email: 'memo@example.com',
  displayName: '메모 사용자',
  loginMethods: ['GOOGLE'],
};

const callbacks = {
  onLinkGoogle: vi.fn(),
  onUnlinkGoogle: vi.fn(),
  onLogout: vi.fn(),
  onClearError: vi.fn(),
};

function render(session: AuthSession): string {
  return renderToStaticMarkup(
    <AccountPanel
      {...callbacks}
      session={session}
      capabilities={{
        registrationEnabled: true,
        googleEnabled: true,
        googleRegistrationEnabled: true,
      }}
      pending={null}
      error={null}
      googleLinked={false}
    />,
  );
}

describe('account login method safety', () => {
  it('does not offer unlink when Google is the only login method', () => {
    const markup = render(baseSession);

    expect(markup).toContain('유일한 로그인 수단');
    expect(markup).not.toContain('Google 연결 해제</button>');
  });

  it('offers an explicit unlink action when a local credential remains', () => {
    const markup = render({ ...baseSession, loginMethods: ['LOCAL', 'GOOGLE'] });

    expect(markup).toContain('Google 연결 해제');
    expect(markup).toContain('이메일·비밀번호');
  });
});
