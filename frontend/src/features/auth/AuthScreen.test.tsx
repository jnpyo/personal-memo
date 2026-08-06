import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { AuthScreen, formAfterAuthModeChange } from './AuthScreen';

const callbacks = {
  onLogin: vi.fn(async () => undefined),
  onRegister: vi.fn(async () => undefined),
  onRetry: vi.fn(),
  onClearError: vi.fn(),
};

describe('authentication screen capabilities', () => {
  it('renders Google sign-in only when the server enables it', () => {
    const enabled = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: true,
          googleRegistrationEnabled: true,
        }}
        connection="online"
        pending={null}
        logoutPending={false}
        error={null}
        redirectError={null}
      />,
    );
    const disabled = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: false,
          googleRegistrationEnabled: false,
        }}
        connection="online"
        pending={null}
        logoutPending={false}
        error={null}
        redirectError={null}
      />,
    );

    expect(enabled).toContain('/oauth2/authorization/google');
    expect(enabled).toContain('Google로 계속하기');
    expect(enabled).toContain('Google 계정으로 새 계정을 만들거나');
    expect(disabled).not.toContain('Google로 계속하기');
  });

  it('warns before OAuth when Google authentication cannot create a new account', () => {
    const markup = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: true,
          googleRegistrationEnabled: false,
        }}
        connection="online"
        pending={null}
        logoutPending={false}
        error={null}
        redirectError={null}
      />,
    );

    expect(markup).toContain('Google 신규 가입은 닫혀 있습니다');
    expect(markup).toContain('자체 로그인 후 계정 설정에서 Google을 연결해 주세요');
  });

  it('disables authentication submission while offline and explains persistence accurately', () => {
    const markup = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: true,
          googleRegistrationEnabled: true,
        }}
        connection="offline"
        pending={null}
        logoutPending={false}
        error={null}
        redirectError={null}
      />,
    );

    expect(markup).toContain('오프라인에서는 로그인하거나 계정을 만들 수 없습니다');
    expect(markup).toContain('입력한 정보는 기기에 저장되지 않습니다');
    expect(markup).toContain('disabled=""');
  });

  it('renders a locked logout confirmation state instead of reopening authentication', () => {
    const markup = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: true,
          googleRegistrationEnabled: true,
        }}
        connection="offline"
        pending="LOGOUT"
        logoutPending
        error="서버 연결이 끊겨 로그아웃을 확인하지 못했습니다."
        redirectError={null}
      />,
    );

    expect(markup).toContain('서버 연결이 끊겨 로그아웃을 확인하지 못했습니다.');
    expect(markup).toContain('로그아웃 다시 시도');
    expect(markup).not.toContain('오프라인에서는 로그인하거나 계정을 만들 수 없습니다');
    expect(markup).toContain('disabled=""');
  });

  it('clears the password whenever the user changes authentication mode', () => {
    expect(formAfterAuthModeChange({
      email: 'memo@example.com',
      password: 'must-not-survive-mode-change',
      displayName: '메모 사용자',
    })).toEqual({
      email: 'memo@example.com',
      password: '',
      displayName: '메모 사용자',
    });
  });

  it('disables Google and mode-switch actions while another auth operation is pending', () => {
    const markup = renderToStaticMarkup(
      <AuthScreen
        {...callbacks}
        capabilities={{
          registrationEnabled: true,
          googleEnabled: true,
          googleRegistrationEnabled: true,
        }}
        connection="online"
        pending="LOGIN"
        logoutPending={false}
        error={null}
        redirectError={null}
      />,
    );

    expect(markup).not.toContain('href="/oauth2/authorization/google"');
    expect(markup.match(/disabled=""/g)?.length).toBeGreaterThanOrEqual(4);
  });
});
