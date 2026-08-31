import { describe, expect, it } from 'vitest';
import { ApiError } from '../../shared/api/errors';
import {
  accountActionErrorMessage,
  PASSWORD_MIN_LENGTH,
  PASSWORD_MAX_UTF8_BYTES,
  parseLoginRedirectError,
  validateLocalAuth,
} from './authModel';

describe('local authentication validation', () => {
  it('requires a valid email and a 12-character password without echoing the password', () => {
    const password = 'short';
    const errors = validateLocalAuth('REGISTER', {
      email: 'not-an-email',
      password,
      displayName: '',
    });

    expect(PASSWORD_MIN_LENGTH).toBe(12);
    expect(errors.email).toContain('이메일');
    expect(errors.password).toContain('12자');
    expect(JSON.stringify(errors)).not.toContain(password);
  });

  it('allows a short existing password at login but enforces bcrypt UTF-8 bytes on registration', () => {
    expect(validateLocalAuth('LOGIN', {
      email: 'memo@example.com',
      password: 'legacy',
      displayName: '',
    })).toEqual({});

    const errors = validateLocalAuth('REGISTER', {
      email: 'memo@example.com',
      password: '가'.repeat(25),
      displayName: '메모 사용자',
    });
    expect(PASSWORD_MAX_UTF8_BYTES).toBe(72);
    expect(errors.password).toContain('72바이트');
  });

  it('requires and bounds the display name only during registration', () => {
    const common = { email: 'memo@example.com', password: 'a-secure-passphrase' };

    expect(validateLocalAuth('LOGIN', { ...common, displayName: '' })).toEqual({});
    expect(validateLocalAuth('REGISTER', { ...common, displayName: '   ' })).toEqual({
      displayName: '표시 이름을 입력해 주세요.',
    });
    expect(
      validateLocalAuth('REGISTER', { ...common, displayName: '가'.repeat(81) }).displayName,
    ).toContain('80자');
  });
});

describe('OAuth login redirect errors', () => {
  it('maps known account-link errors to a safe next action', () => {
    expect(parseLoginRedirectError('?error=account_link_required')).toContain(
      '자체 로그인 후 계정 설정',
    );
    expect(parseLoginRedirectError('?error=GOOGLE_REGISTRATION_DISABLED')).toContain(
      '자체 계정을 만든 뒤',
    );
    expect(parseLoginRedirectError('?error=GOOGLE_IDENTITY_CONFLICT')).toContain(
      '다른 사용자에게 연결',
    );
    expect(parseLoginRedirectError('?error=GOOGLE_EMAIL_NOT_VERIFIED')).toContain(
      '확인된 이메일',
    );
    expect(parseLoginRedirectError('?error=LINK_INTENT_EXPIRED')).toContain('만료');
    expect(parseLoginRedirectError('?error=LINK_INTENT_INVALID')).toContain('확인할 수 없습니다');
    expect(parseLoginRedirectError('?error=OAUTH_FAILED')).toContain('완료하지 못했습니다');
  });

  it('does not expose unknown provider details', () => {
    expect(parseLoginRedirectError('?error=provider_stack_trace')).toBe(
      '로그인을 완료하지 못했습니다. 다시 시도해 주세요.',
    );
    expect(parseLoginRedirectError('')).toBeNull();
  });
});

describe('account action errors', () => {
  it('does not describe an authentication conflict as a memo revision conflict', () => {
    const message = accountActionErrorMessage(new ApiError(
      'Add another login method before unlinking Google.',
      409,
      'LOGIN_METHOD_REQUIRED',
    ));

    expect(message).toContain('이메일·비밀번호 로그인 수단');
    expect(message).not.toContain('메모 상태');
  });

  it('uses an account-specific fallback for other authentication conflicts', () => {
    const message = accountActionErrorMessage(new ApiError(
      'Conflict',
      409,
      'FUTURE_AUTH_CONFLICT',
    ));

    expect(message).toContain('계정의 로그인 수단 상태');
    expect(message).not.toContain('메모 상태');
  });
});
