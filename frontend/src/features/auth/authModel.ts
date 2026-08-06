import { ApiError, errorMessage } from '../../shared/api/errors';

export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_UTF8_BYTES = 72;

export type AuthMode = 'LOGIN' | 'REGISTER';

export type LocalAuthInput = {
  email: string;
  password: string;
  displayName: string;
};

export type LocalAuthErrors = Partial<Record<keyof LocalAuthInput, string>>;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validateLocalAuth(
  mode: AuthMode,
  input: LocalAuthInput,
): LocalAuthErrors {
  const errors: LocalAuthErrors = {};
  const email = input.email.trim();

  if (!email) {
    errors.email = '이메일을 입력해 주세요.';
  } else if (email.length > 254 || !EMAIL_PATTERN.test(email)) {
    errors.email = '올바른 이메일 형식을 입력해 주세요.';
  }

  if (!input.password) {
    errors.password = '비밀번호를 입력해 주세요.';
  } else if (mode === 'REGISTER' && [...input.password].length < PASSWORD_MIN_LENGTH) {
    errors.password = `비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상이어야 합니다.`;
  } else if (
    mode === 'REGISTER' &&
    new TextEncoder().encode(input.password).byteLength > PASSWORD_MAX_UTF8_BYTES
  ) {
    errors.password = `비밀번호는 UTF-8 기준 ${PASSWORD_MAX_UTF8_BYTES}바이트 이하여야 합니다.`;
  }

  if (mode === 'REGISTER') {
    const displayName = input.displayName.trim();
    if (!displayName) {
      errors.displayName = '표시 이름을 입력해 주세요.';
    } else if ([...displayName].length > 80) {
      errors.displayName = '표시 이름은 80자 이하여야 합니다.';
    }
  }

  return errors;
}

export function hasAuthErrors(errors: LocalAuthErrors): boolean {
  return Object.keys(errors).length > 0;
}

export function parseLoginRedirectError(search: string): string | null {
  const error = new URLSearchParams(search).get('error');
  if (!error) return null;

  switch (error.toLowerCase()) {
    case 'oauth_cancelled':
    case 'access_denied':
      return 'Google 로그인이 취소되었습니다.';
    case 'account_link_required':
      return '같은 이메일의 계정이 있습니다. 자체 로그인 후 계정 설정에서 Google을 연결해 주세요.';
    case 'google_registration_disabled':
      return '현재 이 서비스에서는 Google로 새 계정을 만들 수 없습니다. 자체 계정을 만든 뒤 로그인하고 계정 설정에서 Google을 연결해 주세요.';
    case 'google_identity_conflict':
      return '이 Google 계정은 다른 사용자에게 연결되어 있어 연결할 수 없습니다.';
    case 'google_email_not_verified':
      return '확인된 이메일이 있는 Google 계정만 연결할 수 있습니다.';
    case 'link_intent_expired':
      return 'Google 연결 요청이 만료되었습니다. 계정 설정에서 다시 시작해 주세요.';
    case 'link_intent_invalid':
      return 'Google 연결 요청을 확인할 수 없습니다. 계정 설정에서 다시 시작해 주세요.';
    case 'oauth_failed':
    case 'google_login_failed':
      return 'Google 로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.';
    default:
      return '로그인을 완료하지 못했습니다. 다시 시도해 주세요.';
  }
}

export function authErrorMessage(error: unknown, mode: AuthMode): string {
  if (error instanceof ApiError) {
    if (mode === 'LOGIN' && error.status === 401) {
      return '이메일 또는 비밀번호를 확인해 주세요.';
    }
    if (mode === 'REGISTER' && error.status === 409) {
      return '이미 사용 중인 이메일입니다. 로그인하거나 다른 이메일을 사용해 주세요.';
    }
    if (error.status === 429) {
      return '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
    }
  }
  return errorMessage(error);
}

export function accountActionErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'LOGIN_METHOD_REQUIRED') {
      return 'Google 연결을 해제하려면 이메일·비밀번호 로그인 수단을 먼저 유지해야 합니다.';
    }
    if (error.status === 409) {
      return '계정의 로그인 수단 상태가 변경되었습니다. 계정 정보를 다시 확인해 주세요.';
    }
    if (error.status === 401) {
      return '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.';
    }
  }
  return errorMessage(error);
}

export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Seoul';
}
