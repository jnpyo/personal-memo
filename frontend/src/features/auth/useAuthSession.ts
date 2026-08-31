import { useCallback, useEffect, useReducer, useRef } from 'react';
import {
  api,
  AUTHENTICATION_REQUIRED_EVENT,
  SESSION_OWNER_CHANGED_EVENT,
} from '../../shared/api/client';
import { ApiError, errorMessage } from '../../shared/api/errors';
import type { AuthSession } from '../../shared/api/types';
import {
  accountActionErrorMessage,
  authErrorMessage,
  browserTimeZone,
  type LocalAuthInput,
} from './authModel';
import { authReducer, INITIAL_AUTH_STATE } from './authState';
import { AuthOperationGate } from './authOperationGate';
import {
  AuthTransitionChannel,
  createBrowserAuthTransitionTransport,
  type AuthTransitionReason,
  type AuthTransitionSignal,
} from './authTransitionChannel';
import {
  discardStaleLogoutIntent,
  DurableLogoutObservation,
  ephemeralLogoutObservation,
  LogoutAttemptOwnership,
  LogoutCoordinator,
  probeRemoteLogout,
} from './logoutCoordinator';

async function currentSession(): Promise<AuthSession | null> {
  try {
    return await api.authMe();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

export type CrossTabSessionResolution =
  | 'UNCHANGED'
  | 'AUTHENTICATED'
  | 'LOGGED_OUT'
  | 'OWNER_CHANGED'
  | 'UNCONFIRMED';

export function crossTabSessionResolution(
  expectedOwnerId: string | null,
  currentOwnerId: string | null,
): Exclude<CrossTabSessionResolution, 'UNCONFIRMED'> {
  if (currentOwnerId === expectedOwnerId) return 'UNCHANGED';
  if (currentOwnerId === null) return 'LOGGED_OUT';
  if (expectedOwnerId === null) return 'AUTHENTICATED';
  return 'OWNER_CHANGED';
}

export async function probeCrossTabSession(
  expectedOwnerId: string | null,
  readCurrentOwnerId: () => Promise<string | null>,
): Promise<CrossTabSessionResolution> {
  try {
    return crossTabSessionResolution(expectedOwnerId, await readCurrentOwnerId());
  } catch {
    return 'UNCONFIRMED';
  }
}

export function connectionIsOffline(online: boolean | undefined): boolean {
  return online === false;
}

function browserIsOffline(): boolean {
  return typeof navigator !== 'undefined' && connectionIsOffline(navigator.onLine);
}

export function useAuthSession() {
  const [state, dispatch] = useReducer(authReducer, INITIAL_AUTH_STATE);
  const authFlowGenerationRef = useRef(0);
  const logoutCoordinatorRef = useRef<LogoutCoordinator | null>(null);
  const logoutObservationRef = useRef<DurableLogoutObservation | null>(null);
  const logoutOwnershipRef = useRef<LogoutAttemptOwnership | null>(null);
  const localLogoutAttemptIdRef = useRef<string | null>(null);
  const localLogoutOwnerIdRef = useRef<string | null>(null);
  const operationGateRef = useRef<AuthOperationGate | null>(null);
  const latestSessionRef = useRef<AuthSession | null>(null);
  const [observationScheduleVersion, bumpObservationSchedule] = useReducer(
    (version: number) => version + 1,
    0,
  );
  const transitionPublisherRef = useRef<(reason: AuthTransitionReason) => void>(() => undefined);
  latestSessionRef.current = state.session;
  if (!logoutOwnershipRef.current) {
    logoutOwnershipRef.current = new LogoutAttemptOwnership();
  }
  if (!logoutObservationRef.current) {
    logoutObservationRef.current = new DurableLogoutObservation();
  }
  if (!logoutCoordinatorRef.current) {
    logoutCoordinatorRef.current = new LogoutCoordinator(() => api.logout());
    if (logoutCoordinatorRef.current.isPending) {
      logoutOwnershipRef.current.claimLocal();
      localLogoutOwnerIdRef.current = api.pendingLogoutOwner();
      const observation = logoutObservationRef.current.current();
      localLogoutAttemptIdRef.current =
        observation?.ownerId === localLogoutOwnerIdRef.current ? observation.attemptId : null;
    } else {
      const observation = logoutObservationRef.current.current();
      if (observation) logoutOwnershipRef.current.observeRemote(observation);
    }
  }
  if (!operationGateRef.current) operationGateRef.current = new AuthOperationGate();

  const publishTransition = useCallback((reason: AuthTransitionReason) => {
    transitionPublisherRef.current(reason);
  }, []);

  const attemptLogout = useCallback(async (begin: boolean) => {
    const operationToken = operationGateRef.current!.begin();
    if (operationToken === null) return false;
    const generation = ++authFlowGenerationRef.current;
    const coordinator = logoutCoordinatorRef.current!;
    const observationCoordinator = logoutObservationRef.current!;
    const ownership = logoutOwnershipRef.current!;
    if (begin) {
      ownership.claimLocal();
      localLogoutOwnerIdRef.current = latestSessionRef.current?.userId ?? null;
    }
    if (!begin && !ownership.ownsMutationRetry) {
      operationGateRef.current!.end(operationToken);
      return false;
    }
    dispatch({ type: 'LOGOUT_PENDING' });
    const publishPending = () => {
      const observation = begin
        ? (localLogoutOwnerIdRef.current
            ? observationCoordinator.begin(localLogoutOwnerIdRef.current)
            : null)
        : observationCoordinator.resume(
            localLogoutAttemptIdRef.current,
            localLogoutOwnerIdRef.current,
          );
      if (observation) localLogoutAttemptIdRef.current = observation.attemptId;
      publishTransition('LOGOUT_PENDING');
    };
    const attempt = begin
      ? coordinator.begin(publishPending)
      : (() => {
          publishPending();
          return coordinator.retry();
        })();
    if (!attempt) {
      operationGateRef.current!.end(operationToken);
      return false;
    }

    try {
      const result = await attempt;
      if (generation !== authFlowGenerationRef.current) return false;
      if (result.confirmed) {
        if (localLogoutAttemptIdRef.current) {
          observationCoordinator.clear(localLogoutAttemptIdRef.current);
          localLogoutAttemptIdRef.current = null;
        }
        localLogoutOwnerIdRef.current = null;
        ownership.clear();
        api.clearPendingLogoutIntent();
        dispatch({ type: 'LOGGED_OUT' });
        publishTransition('LOGGED_OUT');
        return true;
      }

      const offline = browserIsOffline() || result.error instanceof TypeError;
      dispatch({
        type: 'LOGOUT_RETRY_FAILED',
        connection: offline ? 'offline' : 'online',
        message: offline
          ? '서버 연결이 끊겨 로그아웃을 확인하지 못했습니다. 다시 연결되면 로그아웃을 먼저 재시도합니다.'
          : '서버에서 로그아웃을 확인하지 못했습니다. 로그아웃을 다시 시도해 주세요.',
      });
      return false;
    } finally {
      operationGateRef.current!.end(operationToken);
    }
  }, [publishTransition]);

  const bootstrap = useCallback(async (publishDiscovery = false) => {
    if (operationGateRef.current!.isActive) return;
    const ownership = logoutOwnershipRef.current!;
    if (ownership.observesRemoteAttempt) {
      const observationCoordinator = logoutObservationRef.current!;
      const durableObservation = observationCoordinator.current();
      const observationCanRelease = durableObservation === null && (
        ownership.observesDurableAttempt ||
        (ownership.remoteExpiresAt ?? 0) <= Date.now()
      );
      if (durableObservation) {
        ownership.observeRemote(durableObservation);
        bumpObservationSchedule();
      }

      if (ownership.observesRemoteAttempt) {
        dispatch({ type: 'LOGOUT_PENDING' });
        if (browserIsOffline()) {
          dispatch({
            type: 'LOGOUT_RETRY_FAILED',
            connection: 'offline',
            message: '다른 탭에서 시작한 로그아웃 완료를 확인하려면 서버 연결이 필요합니다.',
          });
          return;
        }

        const observedAttemptId = ownership.remoteAttemptId!;
        const expectedOwnerId = ownership.remoteOwnerId!;
        const probeGeneration = ++authFlowGenerationRef.current;
        try {
          const resolution = await probeRemoteLogout(
            expectedOwnerId,
            async () => (await currentSession())?.userId ?? null,
          );
          if (
            probeGeneration !== authFlowGenerationRef.current ||
            logoutOwnershipRef.current?.remoteAttemptId !== observedAttemptId
          ) return;
          if (resolution === 'PENDING') {
            if (!observationCanRelease) {
              dispatch({
                type: 'LOGOUT_RETRY_FAILED',
                connection: 'online',
                message: '다른 탭에서 로그아웃 완료를 기다리고 있습니다. 시작한 탭에서 다시 시도해 주세요.',
              });
              return;
            }

            observationCoordinator.clear(observedAttemptId);
            ownership.clear();
            logoutCoordinatorRef.current?.cancel();
            api.clearPendingLogoutIntent();
            if (latestSessionRef.current?.userId === expectedOwnerId) {
              api.setSessionOwner(expectedOwnerId);
              dispatch({ type: 'REMOTE_LOGOUT_RELEASED' });
              bumpObservationSchedule();
              return;
            }
            dispatch({ type: 'LOGGED_OUT' });
            bumpObservationSchedule();
          } else {
            observationCoordinator.clear(observedAttemptId);
            ownership.clear();
            logoutCoordinatorRef.current?.cancel();
            api.clearPendingLogoutIntent();
            dispatch({ type: 'LOGGED_OUT' });
            publishTransition(
              resolution === 'OWNER_CHANGED' ? 'SESSION_OWNER_CHANGED' : 'LOGGED_OUT',
            );
            bumpObservationSchedule();
          }
        } catch (error) {
          if (probeGeneration !== authFlowGenerationRef.current) return;
          const offline = browserIsOffline() || error instanceof TypeError;
          dispatch({
            type: 'LOGOUT_RETRY_FAILED',
            connection: offline ? 'offline' : 'online',
            message: offline
              ? '다른 탭에서 시작한 로그아웃 완료를 확인하려면 서버 연결이 필요합니다.'
              : '로그아웃 완료 상태를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.',
          });
          return;
        }
      }
    }
    if (logoutCoordinatorRef.current?.isPending) {
      const logoutConfirmed = await attemptLogout(false);
      if (!logoutConfirmed) return;
    }
    const generation = ++authFlowGenerationRef.current;
    dispatch({ type: 'BOOTING' });
    if (browserIsOffline()) {
      dispatch({ type: 'OFFLINE' });
      return;
    }
    try {
      // Establish the anonymous/session-bound XSRF cookie before parallel reads use it.
      await api.refreshCsrf();
      const [capabilities, session] = await Promise.all([
        api.authCapabilities(),
        currentSession(),
      ]);
      if (
        generation !== authFlowGenerationRef.current ||
        logoutCoordinatorRef.current?.isPending
      ) return;
      api.setSessionOwner(session?.userId ?? null);
      dispatch({ type: 'BOOTSTRAPPED', capabilities, session });
      if (publishDiscovery && session) publishTransition('SESSION_DISCOVERED');
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return;
      if (browserIsOffline() || error instanceof TypeError) {
        dispatch({ type: 'OFFLINE' });
      } else {
        api.invalidateSession();
        dispatch({ type: 'BOOTSTRAP_FAILED', message: errorMessage(error) });
      }
    }
  }, [attemptLogout, publishTransition]);

  const reconcileSessionTransition = useCallback((publishReason?: AuthTransitionReason) => {
    authFlowGenerationRef.current += 1;
    operationGateRef.current!.cancel();
    api.invalidateSession();
    dispatch({ type: 'SESSION_TRANSITION_DETECTED' });
    if (publishReason) publishTransition(publishReason);

    if (logoutOwnershipRef.current?.observesRemoteAttempt) {
      dispatch({ type: 'LOGOUT_PENDING' });
    } else if (logoutCoordinatorRef.current?.isPending) {
      dispatch({ type: 'LOGOUT_PENDING' });
      void attemptLogout(false);
    } else {
      void bootstrap(false);
    }
  }, [attemptLogout, bootstrap, publishTransition]);

  const reconcileCrossTabSession = useCallback(async () => {
    const expectedOwnerId = latestSessionRef.current?.userId ?? null;
    const probeGeneration = authFlowGenerationRef.current;
    const resolution = await probeCrossTabSession(
      expectedOwnerId,
      async () => (await currentSession())?.userId ?? null,
    );
    if (
      probeGeneration !== authFlowGenerationRef.current ||
      (latestSessionRef.current?.userId ?? null) !== expectedOwnerId ||
      resolution === 'UNCHANGED' ||
      resolution === 'UNCONFIRMED'
    ) return;

    reconcileSessionTransition();
  }, [reconcileSessionTransition]);

  const reconcileRemoteLogout = useCallback(async () => {
    const ownership = logoutOwnershipRef.current!;
    if (!ownership.observesRemoteAttempt) return false;
    const observationCoordinator = logoutObservationRef.current!;
    const durableObservation = observationCoordinator.current();
    const observationCanRelease = durableObservation === null && (
      ownership.observesDurableAttempt ||
      (ownership.remoteExpiresAt ?? 0) <= Date.now()
    );
    if (durableObservation) {
      ownership.observeRemote(durableObservation);
      bumpObservationSchedule();
    }

    const observedAttemptId = ownership.remoteAttemptId!;
    const expectedOwnerId = ownership.remoteOwnerId!;
    const generation = ++authFlowGenerationRef.current;

    if (browserIsOffline()) {
      dispatch({
        type: 'LOGOUT_RETRY_FAILED',
        connection: 'offline',
        message: '다른 탭에서 시작한 로그아웃 완료를 확인하려면 서버 연결이 필요합니다.',
      });
      return false;
    }

    try {
      const resolution = await probeRemoteLogout(
        expectedOwnerId,
        async () => (await currentSession())?.userId ?? null,
      );
      if (
        generation !== authFlowGenerationRef.current ||
        logoutOwnershipRef.current?.remoteAttemptId !== observedAttemptId
      ) return false;

      if (resolution === 'PENDING') {
        if (observationCanRelease) {
          observationCoordinator.clear(observedAttemptId);
          ownership.clear();
          logoutCoordinatorRef.current?.cancel();
          api.clearPendingLogoutIntent();
          if (latestSessionRef.current?.userId === expectedOwnerId) {
            api.setSessionOwner(expectedOwnerId);
            dispatch({ type: 'REMOTE_LOGOUT_RELEASED' });
          } else {
            dispatch({ type: 'LOGGED_OUT' });
            void bootstrap(false);
          }
          bumpObservationSchedule();
          return true;
        }
        dispatch({
          type: 'LOGOUT_RETRY_FAILED',
          connection: 'online',
          message: '다른 탭에서 로그아웃 완료를 기다리고 있습니다. 시작한 탭에서 다시 시도해 주세요.',
        });
        return false;
      }

      observationCoordinator.clear(observedAttemptId);
      ownership.clear();
      logoutCoordinatorRef.current?.cancel();
      api.clearPendingLogoutIntent();
      dispatch({ type: 'LOGGED_OUT' });
      publishTransition(resolution === 'OWNER_CHANGED' ? 'SESSION_OWNER_CHANGED' : 'LOGGED_OUT');
      bumpObservationSchedule();
      void bootstrap(false);
      return true;
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return false;
      const offline = browserIsOffline() || error instanceof TypeError;
      dispatch({
        type: 'LOGOUT_RETRY_FAILED',
        connection: offline ? 'offline' : 'online',
        message: offline
          ? '다른 탭에서 시작한 로그아웃 완료를 확인하려면 서버 연결이 필요합니다.'
          : '로그아웃 완료 상태를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.',
      });
      return false;
    }
  }, [bootstrap, publishTransition]);

  const releaseLogoutAndBootstrap = useCallback((publishReason?: AuthTransitionReason) => {
    authFlowGenerationRef.current += 1;
    operationGateRef.current!.cancel();
    const ownership = logoutOwnershipRef.current!;
    const observationAttemptId = ownership.remoteAttemptId ?? localLogoutAttemptIdRef.current;
    if (observationAttemptId) {
      logoutObservationRef.current?.clear(observationAttemptId);
    }
    localLogoutAttemptIdRef.current = null;
    localLogoutOwnerIdRef.current = null;
    ownership.clear();
    logoutCoordinatorRef.current?.cancel();
    api.clearPendingLogoutIntent();
    api.invalidateSession();
    dispatch({ type: 'LOGGED_OUT' });
    if (publishReason) publishTransition(publishReason);
    bumpObservationSchedule();
    void bootstrap(false);
  }, [bootstrap, publishTransition]);

  const handleCrossTabTransition = useCallback((signal: AuthTransitionSignal) => {
    if (signal.reason === 'LOGOUT_PENDING') {
      const ownership = logoutOwnershipRef.current!;
      const durableObservation = logoutObservationRef.current!.current();
      const observedOwnerId = durableObservation?.ownerId ?? latestSessionRef.current?.userId;
      if (observedOwnerId) {
        ownership.observeRemote(
          durableObservation ?? ephemeralLogoutObservation(
            signal.id,
            observedOwnerId,
            signal.emittedAt,
          ),
          durableObservation !== null,
        );
        bumpObservationSchedule();
      }
      if (!ownership.ownsMutationRetry && !ownership.observesRemoteAttempt) {
        reconcileSessionTransition();
        return;
      }
      if (ownership.ownsMutationRetry) {
        dispatch({ type: 'LOGOUT_PENDING' });
        return;
      }

      authFlowGenerationRef.current += 1;
      operationGateRef.current!.cancel();
      api.invalidateSession();
      dispatch({ type: 'LOGOUT_PENDING' });
      if (ownership.observesRemoteAttempt) void reconcileRemoteLogout();
      return;
    }

    if (
      signal.reason === 'LOGGED_OUT' ||
      signal.reason === 'SESSION_EXPIRED' ||
      signal.reason === 'SESSION_OWNER_CHANGED'
    ) {
      releaseLogoutAndBootstrap();
      return;
    }

    if (logoutOwnershipRef.current?.observesRemoteAttempt) {
      void reconcileRemoteLogout();
      return;
    }
    if (signal.reason === 'AUTHENTICATED' || signal.reason === 'SESSION_DISCOVERED') {
      void reconcileCrossTabSession();
      return;
    }
    reconcileSessionTransition();
  }, [
    reconcileCrossTabSession,
    reconcileRemoteLogout,
    reconcileSessionTransition,
    releaseLogoutAndBootstrap,
  ]);

  useEffect(() => {
    const channel = new AuthTransitionChannel(
      createBrowserAuthTransitionTransport(),
      handleCrossTabTransition,
    );
    transitionPublisherRef.current = (reason) => channel.publish(reason);
    return () => {
      transitionPublisherRef.current = () => undefined;
      channel.close();
    };
  }, [handleCrossTabTransition]);

  useEffect(() => {
    const ownership = logoutOwnershipRef.current!;
    const expiresAt = ownership.remoteExpiresAt;
    if (!ownership.observesRemoteAttempt || expiresAt === null) return;

    const timeout = window.setTimeout(() => {
      const current = logoutObservationRef.current!.current();
      if (current) {
        ownership.observeRemote(current);
        bumpObservationSchedule();
        return;
      }
      void reconcileRemoteLogout();
    }, Math.max(0, expiresAt - Date.now()) + 1);
    return () => window.clearTimeout(timeout);
  }, [observationScheduleVersion, reconcileRemoteLogout]);

  useEffect(() => {
    void bootstrap(true);
  }, [bootstrap]);

  useEffect(() => {
    const handleOffline = () => dispatch({ type: 'OFFLINE' });
    const handleOnline = () => {
      if (logoutOwnershipRef.current?.observesRemoteAttempt) {
        void reconcileRemoteLogout();
      } else if (logoutCoordinatorRef.current?.isPending) {
        void attemptLogout(false);
      } else if (!operationGateRef.current!.isActive) {
        void bootstrap(false);
      }
    };
    const handleAuthenticationRequired = () => {
      authFlowGenerationRef.current += 1;
      operationGateRef.current!.cancel();
      api.invalidateSession();
      logoutCoordinatorRef.current?.cancel();
      const ownership = logoutOwnershipRef.current!;
      const observationAttemptId = ownership.remoteAttemptId ?? localLogoutAttemptIdRef.current;
      if (observationAttemptId) {
        logoutObservationRef.current?.clear(observationAttemptId);
      }
      localLogoutAttemptIdRef.current = null;
      localLogoutOwnerIdRef.current = null;
      ownership.clear();
      api.clearPendingLogoutIntent();
      dispatch({
        type: 'LOGGED_OUT',
        message: '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.',
      });
      publishTransition('SESSION_EXPIRED');
      bumpObservationSchedule();
    };
    const handleSessionOwnerChanged = () => {
      discardStaleLogoutIntent(
        logoutCoordinatorRef.current!,
        () => api.clearPendingLogoutIntent(),
      );
      releaseLogoutAndBootstrap('SESSION_OWNER_CHANGED');
    };
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', handleOnline);
    window.addEventListener(AUTHENTICATION_REQUIRED_EVENT, handleAuthenticationRequired);
    window.addEventListener(SESSION_OWNER_CHANGED_EVENT, handleSessionOwnerChanged);
    return () => {
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener(AUTHENTICATION_REQUIRED_EVENT, handleAuthenticationRequired);
      window.removeEventListener(SESSION_OWNER_CHANGED_EVENT, handleSessionOwnerChanged);
    };
  }, [
    attemptLogout,
    bootstrap,
    publishTransition,
    reconcileRemoteLogout,
    releaseLogoutAndBootstrap,
  ]);

  async function login(input: Pick<LocalAuthInput, 'email' | 'password'>) {
    const operationToken = operationGateRef.current!.begin();
    if (operationToken === null) return;
    const generation = ++authFlowGenerationRef.current;
    dispatch({ type: 'BEGIN', operation: 'LOGIN' });
    try {
      const session = await api.login({ email: input.email.trim(), password: input.password });
      if (generation !== authFlowGenerationRef.current) return;
      api.setSessionOwner(session.userId);
      dispatch({ type: 'AUTHENTICATED', session });
      publishTransition('AUTHENTICATED');
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return;
      dispatch({ type: 'FAILED', message: authErrorMessage(error, 'LOGIN') });
    } finally {
      operationGateRef.current!.end(operationToken);
    }
  }

  async function register(input: LocalAuthInput) {
    const operationToken = operationGateRef.current!.begin();
    if (operationToken === null) return;
    const generation = ++authFlowGenerationRef.current;
    dispatch({ type: 'BEGIN', operation: 'REGISTER' });
    try {
      const session = await api.register({
        email: input.email.trim(),
        password: input.password,
        displayName: input.displayName.trim(),
        timeZone: browserTimeZone(),
      });
      if (generation !== authFlowGenerationRef.current) return;
      api.setSessionOwner(session.userId);
      dispatch({ type: 'AUTHENTICATED', session });
      publishTransition('AUTHENTICATED');
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return;
      dispatch({ type: 'FAILED', message: authErrorMessage(error, 'REGISTER') });
    } finally {
      operationGateRef.current!.end(operationToken);
    }
  }

  async function logout() {
    await attemptLogout(true);
  }

  async function linkGoogle() {
    const operationToken = operationGateRef.current!.begin();
    if (operationToken === null) return;
    const generation = ++authFlowGenerationRef.current;
    dispatch({ type: 'BEGIN', operation: 'LINK_GOOGLE' });
    try {
      const { authorizationUrl } = await api.googleLinkIntent();
      if (generation !== authFlowGenerationRef.current) return;
      window.location.assign(authorizationUrl);
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return;
      dispatch({ type: 'FAILED', message: accountActionErrorMessage(error) });
    } finally {
      operationGateRef.current!.end(operationToken);
    }
  }

  async function unlinkGoogle() {
    if (!state.session || state.session.loginMethods.length < 2) {
      dispatch({ type: 'FAILED', message: '로그인 수단을 하나 이상 유지해야 합니다.' });
      return;
    }
    const operationToken = operationGateRef.current!.begin();
    if (operationToken === null) return;
    const generation = ++authFlowGenerationRef.current;
    dispatch({ type: 'BEGIN', operation: 'UNLINK_GOOGLE' });
    try {
      const session = await api.unlinkGoogle();
      if (generation !== authFlowGenerationRef.current) return;
      dispatch({ type: 'SESSION_UPDATED', session });
    } catch (error) {
      if (generation !== authFlowGenerationRef.current) return;
      dispatch({ type: 'FAILED', message: accountActionErrorMessage(error) });
    } finally {
      operationGateRef.current!.end(operationToken);
    }
  }

  const retryBootstrap = useCallback(async () => {
    if (logoutOwnershipRef.current?.observesRemoteAttempt) {
      await reconcileRemoteLogout();
      return;
    }
    if (logoutCoordinatorRef.current?.isPending) {
      const logoutConfirmed = await attemptLogout(false);
      if (!logoutConfirmed) return;
    }
    await bootstrap(false);
  }, [attemptLogout, bootstrap, reconcileRemoteLogout]);

  return {
    ...state,
    retryBootstrap: () => void retryBootstrap(),
    login,
    register,
    logout: () => void logout(),
    linkGoogle: () => void linkGoogle(),
    unlinkGoogle: () => void unlinkGoogle(),
    clearError: () => dispatch({ type: 'CLEAR_ERROR' }),
  };
}
