import { parseLoginRedirectError } from './authModel';

export type AuthNotices = {
  googleLinked: boolean;
  redirectError: string | null;
};

export type AuthNoticeAction =
  | { type: 'CLEAR' }
  | {
      type: 'SESSION_TRANSITION';
      previousUserId: string | null;
      currentUserId: string | null;
    };

const EMPTY_NOTICES: AuthNotices = { googleLinked: false, redirectError: null };

export function createAuthNotices(search: string): AuthNotices {
  return {
    googleLinked: new URLSearchParams(search).get('linked') === 'google',
    redirectError: parseLoginRedirectError(search),
  };
}

export function authNoticeReducer(
  state: AuthNotices,
  action: AuthNoticeAction,
): AuthNotices {
  if (action.type === 'CLEAR') return EMPTY_NOTICES;

  if (
    action.previousUserId !== null &&
    action.previousUserId !== action.currentUserId
  ) {
    return EMPTY_NOTICES;
  }
  return state;
}
