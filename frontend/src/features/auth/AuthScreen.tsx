import { useRef, useState, type FormEvent } from 'react';
import type { AuthCapabilities } from '../../shared/api/types';
import {
  hasAuthErrors,
  PASSWORD_MAX_UTF8_BYTES,
  PASSWORD_MIN_LENGTH,
  validateLocalAuth,
  type AuthMode,
  type LocalAuthErrors,
  type LocalAuthInput,
} from './authModel';
import type { AuthOperation } from './authState';

type Props = {
  capabilities: AuthCapabilities;
  connection: 'checking' | 'online' | 'offline';
  pending: AuthOperation | null;
  logoutPending: boolean;
  error: string | null;
  redirectError: string | null;
  onLogin: (input: Pick<LocalAuthInput, 'email' | 'password'>) => Promise<void>;
  onRegister: (input: LocalAuthInput) => Promise<void>;
  onRetry: () => void;
  onClearError: () => void;
};

const EMPTY_FORM: LocalAuthInput = { email: '', password: '', displayName: '' };

export function formAfterAuthModeChange(form: LocalAuthInput): LocalAuthInput {
  return { ...form, password: '' };
}

export function AuthScreen({
  capabilities,
  connection,
  pending,
  logoutPending,
  error,
  redirectError,
  onLogin,
  onRegister,
  onRetry,
  onClearError,
}: Props) {
  const [mode, setMode] = useState<AuthMode>('LOGIN');
  const [form, setForm] = useState<LocalAuthInput>(EMPTY_FORM);
  const [errors, setErrors] = useState<LocalAuthErrors>({});
  const submitInFlightRef = useRef(false);
  const displayNameRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const interactionsUnavailable =
    logoutPending || connection !== 'online' || pending !== null;
  const formUnavailable = interactionsUnavailable ||
    (mode === 'REGISTER' && !capabilities.registrationEnabled);
  const operationPending = pending === mode;

  function changeMode(nextMode: AuthMode) {
    if (interactionsUnavailable) return;
    setMode(nextMode);
    setForm(formAfterAuthModeChange);
    setErrors({});
    onClearError();
  }

  function update(field: keyof LocalAuthInput, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextErrors = validateLocalAuth(mode, form);
    setErrors(nextErrors);
    if (hasAuthErrors(nextErrors)) {
      if (nextErrors.displayName) displayNameRef.current?.focus();
      else if (nextErrors.email) emailRef.current?.focus();
      else if (nextErrors.password) passwordRef.current?.focus();
      return;
    }
    if (formUnavailable || submitInFlightRef.current) return;

    submitInFlightRef.current = true;
    try {
      if (mode === 'REGISTER') {
        await onRegister(form);
      } else {
        await onLogin(form);
      }
    } finally {
      submitInFlightRef.current = false;
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-intro" aria-labelledby="auth-title">
        <span className="eyebrow">PERSONAL MEMO</span>
        <h1 id="auth-title">내 메모는<br />나만 볼 수 있게.</h1>
        <p>
          원문은 그대로 보존하고, 구조화 제안은 승인한 뒤에만 태그와 할 일로 반영합니다.
        </p>
      </section>

      <section className="auth-card" aria-labelledby="auth-form-title">
        <div className="auth-card__heading">
          <div>
            <span className="eyebrow">{mode === 'LOGIN' ? 'WELCOME BACK' : 'NEW ACCOUNT'}</span>
            <h2 id="auth-form-title">{mode === 'LOGIN' ? '로그인' : '계정 만들기'}</h2>
          </div>
          <span className={`auth-connection auth-connection--${connection}`} role="status">
            {connection === 'online' ? '온라인' : connection === 'checking' ? '연결 확인 중' : '오프라인'}
          </span>
        </div>

        {logoutPending && (
          <div className="auth-notice auth-notice--error" role="alert">
            <p>
              {error ?? '작업 화면은 잠갔습니다. 서버에서 로그아웃 완료를 확인하는 중입니다.'}
            </p>
            <button type="button" className="text-button" onClick={onRetry}>
              로그아웃 다시 시도
            </button>
          </div>
        )}

        {connection === 'offline' && !logoutPending && (
          <div className="auth-notice auth-notice--error" role="alert">
            <p>오프라인에서는 로그인하거나 계정을 만들 수 없습니다. 입력한 정보는 기기에 저장되지 않습니다.</p>
            <button type="button" className="text-button" onClick={onRetry}>연결 다시 확인</button>
          </div>
        )}

        {(error || redirectError) && !logoutPending && (
          <div className="auth-notice auth-notice--error" role="alert">
            {error ?? redirectError}
          </div>
        )}

        <form className="auth-form" onSubmit={(event) => void submit(event)} noValidate>
          {mode === 'REGISTER' && (
            <label>
              <span>표시 이름</span>
              <input
                ref={displayNameRef}
                type="text"
                value={form.displayName}
                autoComplete="name"
                maxLength={80}
                disabled={formUnavailable}
                aria-invalid={Boolean(errors.displayName)}
                aria-describedby={errors.displayName ? 'display-name-error' : undefined}
                onChange={(event) => update('displayName', event.target.value)}
              />
              {errors.displayName && <small id="display-name-error" className="field-error">{errors.displayName}</small>}
            </label>
          )}

          <label>
            <span>이메일</span>
            <input
              ref={emailRef}
              type="email"
              inputMode="email"
              value={form.email}
              autoComplete="email"
              maxLength={254}
              disabled={formUnavailable}
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
              onChange={(event) => update('email', event.target.value)}
            />
            {errors.email && <small id="email-error" className="field-error">{errors.email}</small>}
          </label>

          <label>
            <span>비밀번호</span>
            <input
              ref={passwordRef}
              type="password"
              value={form.password}
              autoComplete={mode === 'LOGIN' ? 'current-password' : 'new-password'}
              minLength={mode === 'REGISTER' ? PASSWORD_MIN_LENGTH : undefined}
              disabled={formUnavailable}
              aria-invalid={Boolean(errors.password)}
              aria-describedby={mode === 'REGISTER' || errors.password ? 'password-help' : undefined}
              onChange={(event) => update('password', event.target.value)}
            />
            <small id="password-help" className={errors.password ? 'field-error' : 'field-help'}>
              {errors.password ?? (mode === 'REGISTER'
                ? `${PASSWORD_MIN_LENGTH}자 이상, UTF-8 기준 ${PASSWORD_MAX_UTF8_BYTES}바이트 이하로 입력해 주세요.`
                : '')}
            </small>
          </label>

          <button type="submit" className="primary-button auth-submit" disabled={formUnavailable}>
            {operationPending
              ? mode === 'LOGIN' ? '로그인 중…' : '계정 만드는 중…'
              : mode === 'LOGIN' ? '로그인' : '계정 만들기'}
          </button>
        </form>

        {capabilities.googleEnabled && (
          <>
            <div className="auth-divider"><span>또는</span></div>
            {!interactionsUnavailable ? (
              <a className="google-auth-button" href="/oauth2/authorization/google">
                <span aria-hidden="true">G</span>
                Google로 계속하기
              </a>
            ) : (
              <button type="button" className="google-auth-button" disabled>
                <span aria-hidden="true">G</span>
                Google로 계속하기
              </button>
            )}
            <p className="google-auth-help">
              {capabilities.googleRegistrationEnabled
                ? 'Google 계정으로 새 계정을 만들거나, 이미 연결한 계정으로 로그인할 수 있습니다.'
                : 'Google 신규 가입은 닫혀 있습니다. 이미 연결한 계정으로 로그인하거나, 자체 로그인 후 계정 설정에서 Google을 연결해 주세요.'}
            </p>
          </>
        )}

        {mode === 'LOGIN' && capabilities.registrationEnabled ? (
          <p className="auth-switch">
            처음이신가요?{' '}
            <button
              type="button"
              className="text-button"
              disabled={interactionsUnavailable}
              onClick={() => changeMode('REGISTER')}
            >
              계정 만들기
            </button>
          </p>
        ) : mode === 'REGISTER' ? (
          <p className="auth-switch">
            이미 계정이 있나요?{' '}
            <button
              type="button"
              className="text-button"
              disabled={interactionsUnavailable}
              onClick={() => changeMode('LOGIN')}
            >
              로그인으로 돌아가기
            </button>
          </p>
        ) : null}
      </section>
    </main>
  );
}
