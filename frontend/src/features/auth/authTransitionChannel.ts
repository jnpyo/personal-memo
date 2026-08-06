export type AuthTransitionReason =
  | 'AUTHENTICATED'
  | 'LOGOUT_PENDING'
  | 'LOGGED_OUT'
  | 'SESSION_DISCOVERED'
  | 'SESSION_EXPIRED'
  | 'SESSION_OWNER_CHANGED';

export type AuthTransitionSignal = {
  version: 1;
  id: string;
  reason: AuthTransitionReason;
  emittedAt: number;
};

export type AuthTransitionTransport = {
  publish: (signal: AuthTransitionSignal) => void;
  subscribe: (listener: (signal: unknown) => void) => () => void;
  close?: () => void;
};

const CHANNEL_NAME = 'personal-memo.auth-transition.v1';
const STORAGE_KEY = 'personal-memo.auth-transition.v1';
const MAX_SEEN_SIGNALS = 64;

function validSignal(value: unknown): value is AuthTransitionSignal {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<AuthTransitionSignal>;
  return candidate.version === 1 &&
    typeof candidate.id === 'string' && candidate.id.length > 0 &&
    typeof candidate.emittedAt === 'number' && Number.isFinite(candidate.emittedAt) &&
    (candidate.reason === 'AUTHENTICATED' ||
      candidate.reason === 'LOGOUT_PENDING' ||
      candidate.reason === 'LOGGED_OUT' ||
      candidate.reason === 'SESSION_DISCOVERED' ||
      candidate.reason === 'SESSION_EXPIRED' ||
      candidate.reason === 'SESSION_OWNER_CHANGED');
}

function browserSignalId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function noOpTransport(): AuthTransitionTransport {
  return {
    publish: () => undefined,
    subscribe: () => () => undefined,
  };
}

export function createBrowserAuthTransitionTransport(): AuthTransitionTransport {
  if (typeof window === 'undefined') return noOpTransport();

  if (typeof BroadcastChannel !== 'undefined') {
    try {
      const channel = new BroadcastChannel(CHANNEL_NAME);
      return {
        publish: (signal) => channel.postMessage(signal),
        subscribe: (listener) => {
          const handleMessage = (event: MessageEvent<unknown>) => listener(event.data);
          channel.addEventListener('message', handleMessage);
          return () => channel.removeEventListener('message', handleMessage);
        },
        close: () => channel.close(),
      };
    } catch {
      // Fall through to the storage-event transport for restricted browsers.
    }
  }

  return {
    publish: (signal) => {
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(signal));
      } catch {
        // Cross-tab synchronization is best-effort when browser storage is blocked.
      }
    },
    subscribe: (listener) => {
      const handleStorage = (event: StorageEvent) => {
        if (event.key !== STORAGE_KEY || !event.newValue) return;
        try {
          listener(JSON.parse(event.newValue) as unknown);
        } catch {
          // Ignore malformed values written by another script or an older release.
        }
      };
      window.addEventListener('storage', handleStorage);
      return () => window.removeEventListener('storage', handleStorage);
    },
  };
}

/**
 * Deduplicates transition signals and deliberately does not deliver a tab's own
 * publication back to its listener, which prevents bootstrap broadcast loops.
 */
export class AuthTransitionChannel {
  private readonly seen = new Set<string>();
  private readonly seenOrder: string[] = [];
  private readonly unsubscribe: () => void;

  constructor(
    private readonly transport: AuthTransitionTransport,
    private readonly onTransition: (signal: AuthTransitionSignal) => void,
    private readonly createId: () => string = browserSignalId,
    private readonly now: () => number = Date.now,
  ) {
    this.unsubscribe = transport.subscribe((signal) => this.receive(signal));
  }

  publish(reason: AuthTransitionReason): void {
    const signal: AuthTransitionSignal = {
      version: 1,
      id: this.createId(),
      reason,
      emittedAt: this.now(),
    };
    this.remember(signal.id);
    this.transport.publish(signal);
  }

  close(): void {
    this.unsubscribe();
    this.transport.close?.();
  }

  private receive(candidate: unknown): void {
    if (!validSignal(candidate) || this.seen.has(candidate.id)) return;
    this.remember(candidate.id);
    this.onTransition(candidate);
  }

  private remember(id: string): void {
    if (this.seen.has(id)) return;
    this.seen.add(id);
    this.seenOrder.push(id);
    if (this.seenOrder.length > MAX_SEEN_SIGNALS) {
      const oldest = this.seenOrder.shift();
      if (oldest) this.seen.delete(oldest);
    }
  }
}
