import type { AuthCapabilities, AuthSession } from '../../shared/api/types';

export type AuthOperation = 'LOGIN' | 'REGISTER' | 'LOGOUT' | 'LINK_GOOGLE' | 'UNLINK_GOOGLE';

export type AuthState = {
  status: 'BOOTING' | 'UNAUTHENTICATED' | 'AUTHENTICATED' | 'LOGOUT_PENDING';
  connection: 'checking' | 'online' | 'offline';
  capabilities: AuthCapabilities;
  session: AuthSession | null;
  pending: AuthOperation | null;
  error: string | null;
};

export type AuthAction =
  | { type: 'BOOTING' }
  | {
      type: 'BOOTSTRAPPED';
      capabilities: AuthCapabilities;
      session: AuthSession | null;
    }
  | { type: 'OFFLINE'; message?: string }
  | { type: 'BOOTSTRAP_FAILED'; message: string }
  | { type: 'SESSION_TRANSITION_DETECTED' }
  | { type: 'BEGIN'; operation: AuthOperation }
  | { type: 'LOGOUT_PENDING' }
  | {
      type: 'LOGOUT_RETRY_FAILED';
      connection: 'online' | 'offline';
      message: string;
    }
  | { type: 'REMOTE_LOGOUT_RELEASED' }
  | { type: 'AUTHENTICATED'; session: AuthSession }
  | { type: 'SESSION_UPDATED'; session: AuthSession }
  | { type: 'FAILED'; message: string }
  | { type: 'LOGGED_OUT'; message?: string }
  | { type: 'CLEAR_ERROR' };

export const INITIAL_AUTH_STATE: AuthState = {
  status: 'BOOTING',
  connection: 'checking',
  capabilities: {
    registrationEnabled: false,
    googleEnabled: false,
    googleRegistrationEnabled: false,
  },
  session: null,
  pending: null,
  error: null,
};

export function authReducer(state: AuthState, action: AuthAction): AuthState {
  if (
    state.status === 'LOGOUT_PENDING' &&
    action.type !== 'LOGOUT_PENDING' &&
    action.type !== 'LOGOUT_RETRY_FAILED' &&
    action.type !== 'REMOTE_LOGOUT_RELEASED' &&
    action.type !== 'LOGGED_OUT' &&
    action.type !== 'OFFLINE' &&
    action.type !== 'CLEAR_ERROR'
  ) {
    return state;
  }

  switch (action.type) {
    case 'BOOTING':
      return { ...state, status: state.session ? 'AUTHENTICATED' : 'BOOTING', connection: 'checking', error: null };
    case 'BOOTSTRAPPED':
      return {
        ...state,
        status: action.session ? 'AUTHENTICATED' : 'UNAUTHENTICATED',
        connection: 'online',
        capabilities: action.capabilities,
        session: action.session,
        pending: null,
        error: null,
      };
    case 'OFFLINE':
      if (state.status === 'LOGOUT_PENDING') {
        return {
          ...state,
          connection: 'offline',
          pending: 'LOGOUT',
          error: action.message ?? state.error,
        };
      }
      return {
        ...state,
        status: state.session ? 'AUTHENTICATED' : 'UNAUTHENTICATED',
        connection: 'offline',
        error: action.message ?? state.error,
      };
    case 'BOOTSTRAP_FAILED':
      return {
        ...state,
        status: 'UNAUTHENTICATED',
        connection: 'online',
        session: null,
        pending: null,
        error: action.message,
      };
    case 'SESSION_TRANSITION_DETECTED':
      return {
        ...state,
        status: 'BOOTING',
        connection: 'checking',
        session: null,
        pending: null,
        error: null,
      };
    case 'BEGIN':
      return { ...state, pending: action.operation, error: null };
    case 'LOGOUT_PENDING':
      return {
        ...state,
        status: 'LOGOUT_PENDING',
        pending: 'LOGOUT',
        error: null,
      };
    case 'LOGOUT_RETRY_FAILED':
      return {
        ...state,
        status: 'LOGOUT_PENDING',
        connection: action.connection,
        pending: 'LOGOUT',
        error: action.message,
      };
    case 'REMOTE_LOGOUT_RELEASED':
      return state.session
        ? {
            ...state,
            status: 'AUTHENTICATED',
            connection: 'online',
            pending: null,
            error: null,
          }
        : state;
    case 'AUTHENTICATED':
      return {
        ...state,
        status: 'AUTHENTICATED',
        connection: 'online',
        session: action.session,
        pending: null,
        error: null,
      };
    case 'SESSION_UPDATED':
      return { ...state, session: action.session, pending: null, error: null };
    case 'FAILED':
      return { ...state, pending: null, error: action.message };
    case 'LOGGED_OUT':
      return {
        ...state,
        status: 'UNAUTHENTICATED',
        connection: 'online',
        session: null,
        pending: null,
        error: action.message ?? null,
      };
    case 'CLEAR_ERROR':
      return { ...state, error: null };
  }
}
