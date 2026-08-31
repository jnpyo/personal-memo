export type LogoutAttemptResult =
  | { confirmed: true }
  | { confirmed: false; error: unknown };

export type LogoutPendingStore = {
  read: () => boolean;
  write: (pending: boolean) => void;
};

export type LogoutAttemptRole = 'NONE' | 'LOCAL_OWNER' | 'REMOTE_OBSERVER';
export type RemoteLogoutResolution = 'PENDING' | 'LOGGED_OUT' | 'OWNER_CHANGED';

export type LogoutObservation = {
  version: 1;
  attemptId: string;
  ownerId: string;
  expiresAt: number;
};

export type LogoutObservationStore = {
  read: () => unknown;
  write: (observation: LogoutObservation) => void;
  remove: () => void;
};

export const LOGOUT_OBSERVATION_TTL_MS = 5 * 60 * 1000;

export function remoteLogoutResolution(
  expectedOwnerId: string | null,
  currentOwnerId: string | null,
): RemoteLogoutResolution {
  if (currentOwnerId === null) return 'LOGGED_OUT';
  if (expectedOwnerId !== null && currentOwnerId !== expectedOwnerId) return 'OWNER_CHANGED';
  return 'PENDING';
}

/**
 * Separates the tab that owns the logout HTTP retry from tabs that only need
 * to hide their workspace. A remote observer must never issue the mutation;
 * it may only probe the current session until the owning tab broadcasts a
 * terminal transition.
 */
export class LogoutAttemptOwnership {
  private role: LogoutAttemptRole = 'NONE';
  private observation: LogoutObservation | null = null;
  private durableObservation = false;

  claimLocal(): void {
    this.role = 'LOCAL_OWNER';
    this.observation = null;
    this.durableObservation = false;
  }

  observeRemote(observation: LogoutObservation, durable = true): void {
    if (this.role === 'LOCAL_OWNER') return;
    this.role = 'REMOTE_OBSERVER';
    this.observation = observation;
    this.durableObservation = durable;
  }

  clear(): void {
    this.role = 'NONE';
    this.observation = null;
    this.durableObservation = false;
  }

  get currentRole(): LogoutAttemptRole {
    return this.role;
  }

  get remoteOwnerId(): string | null {
    return this.role === 'REMOTE_OBSERVER' ? this.observation?.ownerId ?? null : null;
  }

  get remoteAttemptId(): string | null {
    return this.role === 'REMOTE_OBSERVER' ? this.observation?.attemptId ?? null : null;
  }

  get remoteExpiresAt(): number | null {
    return this.role === 'REMOTE_OBSERVER' ? this.observation?.expiresAt ?? null : null;
  }

  get observesDurableAttempt(): boolean {
    return this.role === 'REMOTE_OBSERVER' && this.durableObservation;
  }

  get ownsMutationRetry(): boolean {
    return this.role === 'LOCAL_OWNER';
  }

  get observesRemoteAttempt(): boolean {
    return this.role === 'REMOTE_OBSERVER';
  }
}

const LOGOUT_PENDING_STORAGE_KEY = 'personal-memo.logout-pending.v1';
const LOGOUT_OBSERVATION_STORAGE_KEY = 'personal-memo.logout-observation.v1';

function browserAttemptId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

const browserLogoutObservationStore: LogoutObservationStore = {
  read: () => {
    if (typeof window === 'undefined') return null;
    try {
      const serialized = window.localStorage.getItem(LOGOUT_OBSERVATION_STORAGE_KEY);
      return serialized === null ? null : JSON.parse(serialized) as unknown;
    } catch {
      return null;
    }
  },
  write: (observation) => {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(LOGOUT_OBSERVATION_STORAGE_KEY, JSON.stringify(observation));
    } catch {
      // BroadcastChannel still protects already-open tabs when durable storage
      // is unavailable in a hardened/private browser context.
    }
  },
  remove: () => {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.removeItem(LOGOUT_OBSERVATION_STORAGE_KEY);
    } catch {
      // The observation will age out logically even if storage removal fails.
    }
  },
};

function validLogoutObservation(value: unknown): value is LogoutObservation {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<LogoutObservation>;
  return candidate.version === 1 &&
    typeof candidate.attemptId === 'string' && candidate.attemptId.length > 0 &&
    typeof candidate.ownerId === 'string' && candidate.ownerId.length > 0 &&
    typeof candidate.expiresAt === 'number' && Number.isFinite(candidate.expiresAt);
}

/**
 * Shares only an observation lock between tabs. The mutation intent itself is
 * deliberately kept in the initiating tab's sessionStorage by
 * {@link LogoutCoordinator}; seeing this marker never grants POST/retry rights.
 */
export class DurableLogoutObservation {
  constructor(
    private readonly store: LogoutObservationStore = browserLogoutObservationStore,
    private readonly now: () => number = Date.now,
    private readonly createId: () => string = browserAttemptId,
    private readonly ttlMs: number = LOGOUT_OBSERVATION_TTL_MS,
  ) {}

  begin(ownerId: string): LogoutObservation {
    const observation: LogoutObservation = {
      version: 1,
      attemptId: this.createId(),
      ownerId,
      expiresAt: this.now() + this.ttlMs,
    };
    this.store.write(observation);
    return observation;
  }

  current(): LogoutObservation | null {
    const candidate = this.store.read();
    if (!validLogoutObservation(candidate)) {
      if (candidate !== null && candidate !== undefined) this.store.remove();
      return null;
    }
    if (candidate.expiresAt <= this.now()) {
      this.clear(candidate.attemptId);
      return null;
    }
    return candidate;
  }

  renew(attemptId: string): LogoutObservation | null {
    const current = this.current();
    if (!current || current.attemptId !== attemptId) return null;
    const renewed = { ...current, expiresAt: this.now() + this.ttlMs };
    this.store.write(renewed);
    return renewed;
  }

  resume(attemptId: string | null, ownerId: string | null): LogoutObservation | null {
    if (!ownerId) return null;
    const current = this.current();
    if (!current) return this.begin(ownerId);
    if (!attemptId || current.attemptId !== attemptId || current.ownerId !== ownerId) return null;
    const renewed = { ...current, expiresAt: this.now() + this.ttlMs };
    this.store.write(renewed);
    return renewed;
  }

  clear(attemptId: string): void {
    const candidate = this.store.read();
    if (validLogoutObservation(candidate) && candidate.attemptId === attemptId) {
      this.store.remove();
    }
  }
}

export function ephemeralLogoutObservation(
  attemptId: string,
  ownerId: string,
  emittedAt: number,
): LogoutObservation {
  return {
    version: 1,
    attemptId,
    ownerId,
    expiresAt: emittedAt + LOGOUT_OBSERVATION_TTL_MS,
  };
}

export async function probeRemoteLogout(
  expectedOwnerId: string,
  readCurrentOwnerId: () => Promise<string | null>,
): Promise<RemoteLogoutResolution> {
  return remoteLogoutResolution(expectedOwnerId, await readCurrentOwnerId());
}

const browserLogoutPendingStore: LogoutPendingStore = {
  read: () => {
    if (typeof window === 'undefined') return false;
    try {
      return window.sessionStorage.getItem(LOGOUT_PENDING_STORAGE_KEY) === '1';
    } catch {
      return false;
    }
  },
  write: (pending) => {
    if (typeof window === 'undefined') return;
    try {
      if (pending) {
        window.sessionStorage.setItem(LOGOUT_PENDING_STORAGE_KEY, '1');
      } else {
        window.sessionStorage.removeItem(LOGOUT_PENDING_STORAGE_KEY);
      }
    } catch {
      // Storage can be unavailable in hardened/private browser contexts. The
      // in-memory lock still protects the current page lifetime.
    }
  },
};

/**
 * Keeps an unconfirmed logout sticky across network failures. A reconnect must
 * retry this operation before authentication bootstrap is allowed to run.
 */
export class LogoutCoordinator {
  private pending: boolean;
  private inFlight: Promise<LogoutAttemptResult> | null = null;
  private attemptGeneration = 0;

  constructor(
    private readonly logoutRequest: () => Promise<void>,
    private readonly pendingStore: LogoutPendingStore = browserLogoutPendingStore,
  ) {
    this.pending = pendingStore.read();
  }

  get isPending(): boolean {
    return this.pending;
  }

  begin(onPending?: () => void): Promise<LogoutAttemptResult> {
    this.setPending(true);
    onPending?.();
    return this.attempt();
  }

  retry(): Promise<LogoutAttemptResult> | null {
    return this.pending ? this.attempt() : null;
  }

  cancel(): void {
    this.attemptGeneration += 1;
    this.setPending(false);
    this.inFlight = null;
  }

  private setPending(pending: boolean): void {
    this.pending = pending;
    this.pendingStore.write(pending);
  }

  private attempt(): Promise<LogoutAttemptResult> {
    if (this.inFlight) return this.inFlight;

    const generation = ++this.attemptGeneration;
    const attempt = this.logoutRequest()
      .then<LogoutAttemptResult>(() => {
        if (generation === this.attemptGeneration) this.setPending(false);
        return { confirmed: true };
      })
      .catch<LogoutAttemptResult>((error: unknown) => ({ confirmed: false, error }))
      .finally(() => {
        if (generation === this.attemptGeneration && this.inFlight === attempt) {
          this.inFlight = null;
        }
      });
    this.inFlight = attempt;
    return attempt;
  }
}

/**
 * A server owner-mismatch response proves that this tab's saved logout intent
 * belongs to an older account. Retrying it can never log out that older account,
 * so release both locks before bootstrapping the session that now owns the cookie.
 */
export function discardStaleLogoutIntent(
  coordinator: LogoutCoordinator,
  clearExpectedOwner: () => void,
): void {
  coordinator.cancel();
  clearExpectedOwner();
}
