import { useState } from 'react';
import type { AuthCapabilities, AuthSession } from '../../shared/api/types';
import type { AuthOperation } from './authState';

type Props = {
  session: AuthSession;
  capabilities: AuthCapabilities;
  pending: AuthOperation | null;
  error: string | null;
  googleLinked: boolean;
  onLinkGoogle: () => void;
  onUnlinkGoogle: () => void;
  onLogout: () => void;
  onClearError: () => void;
  interactionDisabled?: boolean;
};

const METHOD_LABEL = { LOCAL: '이메일·비밀번호', GOOGLE: 'Google' } as const;

export function AccountPanel({
  session,
  capabilities,
  pending,
  error,
  googleLinked,
  onLinkGoogle,
  onUnlinkGoogle,
  onLogout,
  onClearError,
  interactionDisabled = false,
}: Props) {
  const [confirmUnlink, setConfirmUnlink] = useState(false);
  const [panelOpen, setPanelOpen] = useState(googleLinked || error !== null);
  const hasGoogle = session.loginMethods.includes('GOOGLE');
  const canUnlinkGoogle = hasGoogle && session.loginMethods.length > 1;

  function closeUnlinkConfirmation() {
    setConfirmUnlink(false);
    onClearError();
  }

  return (
    <details
      className="account-panel"
      open={panelOpen}
      onToggle={(event) => setPanelOpen(event.currentTarget.open)}
    >
      <summary aria-label="계정 메뉴 열기">
        <span aria-hidden="true">{session.displayName.trim().slice(0, 1).toUpperCase() || '?'}</span>
        <strong>{session.displayName}</strong>
      </summary>
      <div className="account-panel__body">
        <div>
          <span className="eyebrow">ACCOUNT</span>
          <h2>계정 설정</h2>
          <p>{session.email}</p>
        </div>

        {googleLinked && (
          <div className="account-success">
            <p role="status">Google 계정을 연결했습니다.</p>
            <button type="button" className="text-button" onClick={onClearError}>알림 닫기</button>
          </div>
        )}
        {error && (
          <div className="account-error" role="alert">
            <p>{error}</p>
            <button type="button" className="text-button" onClick={onClearError}>알림 닫기</button>
          </div>
        )}

        <div className="login-methods" aria-label="로그인 수단">
          {session.loginMethods.map((method) => (
            <span key={method}>{METHOD_LABEL[method]}</span>
          ))}
        </div>

        {capabilities.googleEnabled && !hasGoogle && (
          <button
            type="button"
            className="secondary-button account-action"
            disabled={pending !== null || interactionDisabled}
            onClick={onLinkGoogle}
          >
            {pending === 'LINK_GOOGLE' ? 'Google 연결 준비 중…' : 'Google 계정 연결'}
          </button>
        )}

        {hasGoogle && !canUnlinkGoogle && (
          <p className="account-help">Google이 유일한 로그인 수단이므로 연결을 해제할 수 없습니다.</p>
        )}

        {canUnlinkGoogle && !confirmUnlink && (
          <button
            type="button"
            className="secondary-button account-action"
            disabled={pending !== null || interactionDisabled}
            onClick={() => setConfirmUnlink(true)}
          >
            Google 연결 해제
          </button>
        )}

        {canUnlinkGoogle && confirmUnlink && (
          <div className="account-confirm" role="group" aria-label="Google 연결 해제 확인">
            <p>앞으로 이메일과 비밀번호로만 로그인하게 됩니다.</p>
            <div>
              <button
                type="button"
                className="danger-button"
                disabled={pending !== null || interactionDisabled}
                onClick={() => {
                  setConfirmUnlink(false);
                  onUnlinkGoogle();
                }}
              >
                {pending === 'UNLINK_GOOGLE' ? '해제 중…' : '연결 해제 확인'}
              </button>
              <button
                type="button"
                className="secondary-button"
                disabled={interactionDisabled}
                onClick={closeUnlinkConfirmation}
              >
                취소
              </button>
            </div>
          </div>
        )}

        <button
          type="button"
          className="text-button account-logout"
          disabled={pending !== null || interactionDisabled}
          onClick={onLogout}
        >
          {pending === 'LOGOUT' ? '로그아웃 중…' : '로그아웃'}
        </button>
      </div>
    </details>
  );
}
