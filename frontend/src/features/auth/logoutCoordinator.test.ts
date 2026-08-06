import { describe, expect, it, vi } from 'vitest';
import {
  discardStaleLogoutIntent,
  DurableLogoutObservation,
  LogoutAttemptOwnership,
  LogoutCoordinator,
  probeRemoteLogout,
  remoteLogoutResolution,
  type LogoutObservation,
  type LogoutObservationStore,
  type LogoutPendingStore,
} from './logoutCoordinator';

function memoryStore(initial = false): LogoutPendingStore & { value: boolean } {
  return {
    value: initial,
    read() {
      return this.value;
    },
    write(pending) {
      this.value = pending;
    },
  };
}

function observationStore(initial: unknown = null): LogoutObservationStore & { value: unknown } {
  return {
    value: initial,
    read() {
      return this.value;
    },
    write(observation) {
      this.value = observation;
    },
    remove() {
      this.value = null;
    },
  };
}

function observation(overrides: Partial<LogoutObservation> = {}): LogoutObservation {
  return {
    version: 1,
    attemptId: 'attempt-a',
    ownerId: 'user-a',
    expiresAt: 10_000,
    ...overrides,
  };
}

describe('LogoutCoordinator', () => {
  it('broadcasts the pending lock after persistence but before starting the mutation', async () => {
    const events: string[] = [];
    const store = memoryStore();
    const coordinator = new LogoutCoordinator(async () => {
      events.push('request');
    }, store);

    await coordinator.begin(() => {
      expect(store.value).toBe(true);
      events.push('broadcast');
    });

    expect(events).toEqual(['broadcast', 'request']);
  });

  it('keeps logout pending after a network failure and retries it on reconnect', async () => {
    const logoutRequest = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(undefined);
    const coordinator = new LogoutCoordinator(logoutRequest);

    await expect(coordinator.begin()).resolves.toEqual({
      confirmed: false,
      error: expect.any(TypeError),
    });
    expect(coordinator.isPending).toBe(true);

    await expect(coordinator.retry()).resolves.toEqual({ confirmed: true });
    expect(logoutRequest).toHaveBeenCalledTimes(2);
    expect(coordinator.isPending).toBe(false);
  });

  it('does not turn an HTTP 500 logout failure into a logged-out state', async () => {
    const serverError = new Error('500');
    const coordinator = new LogoutCoordinator(vi.fn().mockRejectedValue(serverError));

    await expect(coordinator.begin()).resolves.toEqual({
      confirmed: false,
      error: serverError,
    });
    expect(coordinator.isPending).toBe(true);
  });

  it('coalesces reconnect events while the logout retry is still in flight', async () => {
    let confirmLogout!: () => void;
    const response = new Promise<void>((resolve) => {
      confirmLogout = resolve;
    });
    const logoutRequest = vi.fn(() => response);
    const coordinator = new LogoutCoordinator(logoutRequest);

    const first = coordinator.begin();
    const reconnect = coordinator.retry();

    expect(reconnect).toBe(first);
    expect(logoutRequest).toHaveBeenCalledTimes(1);
    confirmLogout();
    await expect(first).resolves.toEqual({ confirmed: true });
    expect(coordinator.isPending).toBe(false);
  });

  it('restores an unconfirmed logout lock after a page reload and clears it only on success', async () => {
    const store = memoryStore();
    const failedPage = new LogoutCoordinator(
      vi.fn().mockRejectedValue(new TypeError('offline')),
      store,
    );

    await failedPage.begin();
    expect(store.value).toBe(true);

    const reloadedPage = new LogoutCoordinator(vi.fn().mockResolvedValue(undefined), store);
    expect(reloadedPage.isPending).toBe(true);
    await expect(reloadedPage.retry()).resolves.toEqual({ confirmed: true });
    expect(store.value).toBe(false);
  });

  it('does not let a canceled old success clear a newer logout marker', async () => {
    let confirmOldAttempt!: () => void;
    const oldAttempt = new Promise<void>((resolve) => {
      confirmOldAttempt = resolve;
    });
    const logoutRequest = vi
      .fn<() => Promise<void>>()
      .mockReturnValueOnce(oldAttempt)
      .mockRejectedValueOnce(new TypeError('new attempt is offline'));
    const store = memoryStore();
    const coordinator = new LogoutCoordinator(logoutRequest, store);

    const oldResult = coordinator.begin();
    coordinator.cancel();
    const newResult = coordinator.begin();
    await expect(newResult).resolves.toEqual({
      confirmed: false,
      error: expect.any(TypeError),
    });
    expect(store.value).toBe(true);

    confirmOldAttempt();
    await expect(oldResult).resolves.toEqual({ confirmed: true });
    expect(coordinator.isPending).toBe(true);
    expect(store.value).toBe(true);
  });

  it('terminates a stale logout intent after a session owner mismatch', async () => {
    const store = memoryStore();
    const logoutRequest = vi.fn().mockRejectedValue(new Error('SESSION_OWNER_CHANGED'));
    const clearExpectedOwner = vi.fn();
    const coordinator = new LogoutCoordinator(logoutRequest, store);
    await coordinator.begin();
    expect(coordinator.isPending).toBe(true);

    discardStaleLogoutIntent(coordinator, clearExpectedOwner);

    expect(coordinator.isPending).toBe(false);
    expect(store.value).toBe(false);
    expect(clearExpectedOwner).toHaveBeenCalledTimes(1);
    expect(coordinator.retry()).toBeNull();
    expect(logoutRequest).toHaveBeenCalledTimes(1);
  });
});

describe('LogoutAttemptOwnership', () => {
  it('allows only the initiating tab to own mutation retries', () => {
    const initiatingTab = new LogoutAttemptOwnership();
    const receivingTab = new LogoutAttemptOwnership();

    initiatingTab.claimLocal();
    receivingTab.observeRemote(observation());

    expect(initiatingTab.ownsMutationRetry).toBe(true);
    expect(receivingTab.ownsMutationRetry).toBe(false);
    expect(receivingTab.observesRemoteAttempt).toBe(true);
    expect(receivingTab.remoteOwnerId).toBe('user-a');
  });

  it('does not downgrade a simultaneous local attempt when another pending signal arrives', () => {
    const ownership = new LogoutAttemptOwnership();
    ownership.claimLocal();
    ownership.observeRemote(observation());

    expect(ownership.currentRole).toBe('LOCAL_OWNER');
    expect(ownership.ownsMutationRetry).toBe(true);
  });

  it('keeps observers locked until logout or an owner change is proven', () => {
    expect(remoteLogoutResolution('user-a', 'user-a')).toBe('PENDING');
    expect(remoteLogoutResolution(null, 'user-a')).toBe('PENDING');
    expect(remoteLogoutResolution('user-a', null)).toBe('LOGGED_OUT');
    expect(remoteLogoutResolution('user-a', 'user-b')).toBe('OWNER_CHANGED');
  });
});

describe('DurableLogoutObservation', () => {
  it('lets a newly loaded receiving tab recover a missed pending signal without mutation ownership', () => {
    const sharedStorage = observationStore();
    const initiatingTab = new DurableLogoutObservation(
      sharedStorage,
      () => 1_000,
      () => 'logout-attempt',
      5_000,
    );
    const marker = initiatingTab.begin('user-a');

    // A new instance models a tab opened after the one-shot channel signal.
    const reloadedReceivingTab = new DurableLogoutObservation(sharedStorage, () => 1_001);
    const recovered = reloadedReceivingTab.current();
    const ownership = new LogoutAttemptOwnership();
    ownership.observeRemote(recovered!);

    expect(recovered).toEqual(marker);
    expect(ownership.observesRemoteAttempt).toBe(true);
    expect(ownership.ownsMutationRetry).toBe(false);
    expect(ownership.remoteOwnerId).toBe('user-a');
    expect(ownership.remoteAttemptId).toBe('logout-attempt');
  });

  it('keeps a receiving tab observation distinct from the initiator-only mutation intent', async () => {
    const sharedStorage = observationStore();
    const initiatorMutationStore = memoryStore();
    const receiverMutationStore = memoryStore();
    const initiatorPost = vi.fn().mockRejectedValue(new TypeError('offline'));
    const receiverPost = vi.fn().mockResolvedValue(undefined);
    const initiator = new LogoutCoordinator(initiatorPost, initiatorMutationStore);
    const receiver = new LogoutCoordinator(receiverPost, receiverMutationStore);
    const shared = new DurableLogoutObservation(
      sharedStorage,
      () => 1_000,
      () => 'logout-attempt',
      5_000,
    );

    await initiator.begin(() => shared.begin('user-a'));
    const recovered = new DurableLogoutObservation(sharedStorage, () => 1_001).current();
    const receiverOwnership = new LogoutAttemptOwnership();
    receiverOwnership.observeRemote(recovered!);

    expect(initiatorMutationStore.value).toBe(true);
    expect(receiverMutationStore.value).toBe(false);
    expect(receiverOwnership.ownsMutationRetry).toBe(false);
    expect(receiver.retry()).toBeNull();
    expect(receiverPost).not.toHaveBeenCalled();
  });

  it('clears an expired marker and does not let an older attempt clear a replacement', () => {
    const sharedStorage = observationStore(observation({ expiresAt: 999 }));
    const reader = new DurableLogoutObservation(sharedStorage, () => 1_000);

    expect(reader.current()).toBeNull();
    expect(sharedStorage.value).toBeNull();

    sharedStorage.value = observation({ attemptId: 'newer-attempt', expiresAt: 2_000 });
    reader.clear('older-attempt');
    expect(sharedStorage.value).toEqual(observation({
      attemptId: 'newer-attempt',
      expiresAt: 2_000,
    }));
  });

  it('renews only the initiating attempt that still owns the shared marker', () => {
    const sharedStorage = observationStore(observation({ expiresAt: 1_500 }));
    const owner = new DurableLogoutObservation(sharedStorage, () => 1_000, undefined, 5_000);

    expect(owner.renew('attempt-a')).toEqual(observation({ expiresAt: 6_000 }));
    expect(owner.renew('another-attempt')).toBeNull();
  });

  it('recreates an expired initiating marker without overwriting another live attempt', () => {
    const sharedStorage = observationStore(observation({ expiresAt: 999 }));
    const owner = new DurableLogoutObservation(
      sharedStorage,
      () => 1_000,
      () => 'replacement-attempt',
      5_000,
    );

    expect(owner.resume('attempt-a', 'user-a')).toEqual(observation({
      attemptId: 'replacement-attempt',
      expiresAt: 6_000,
    }));

    sharedStorage.value = observation({
      attemptId: 'other-live-attempt',
      ownerId: 'user-b',
      expiresAt: 8_000,
    });
    expect(owner.resume('replacement-attempt', 'user-a')).toBeNull();
    expect(sharedStorage.value).toEqual(observation({
      attemptId: 'other-live-attempt',
      ownerId: 'user-b',
      expiresAt: 8_000,
    }));
  });
});

describe('probeRemoteLogout', () => {
  it('gives a receiving tab only a current-session reader and never mutation capability', async () => {
    const requests: Array<{ method: string; path: string }> = [];

    const resolution = await probeRemoteLogout('user-a', async () => {
      requests.push({ method: 'GET', path: '/api/v1/auth/me' });
      return 'user-a';
    });

    expect(resolution).toBe('PENDING');
    expect(requests).toEqual([{ method: 'GET', path: '/api/v1/auth/me' }]);
  });
});
