import { describe, expect, it, vi } from 'vitest';
import { createApiClient, SessionScopeChangedError } from './client';

const session = {
  userId: '0711213d-a079-4dc1-92af-f123ba60e45a',
  email: 'memo@example.com',
  displayName: '메모 사용자',
  loginMethods: ['LOCAL'] as const,
};

function json(body: unknown, status = 200, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function csrf(token: string): Response {
  return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token });
}

function noContent(): Response {
  return new Response(null, { status: 204 });
}

describe('authentication API CSRF handling', () => {
  it('loads a token before login, sends the server-provided header, and refreshes after login', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('before-login'))
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(csrf('after-login'));
    const client = createApiClient(fetchMock);

    await expect(client.login({ email: session.email, password: 'a-secure-passphrase' }))
      .resolves.toEqual(session);

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', {
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'before-login',
      },
      body: JSON.stringify({ email: session.email, password: 'a-secure-passphrase' }),
    });
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/v1/auth/csrf', {
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    });
  });

  it('refreshes and retries a mutation exactly once for a CSRF-specific 403', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('expired'))
      .mockResolvedValueOnce(json({ code: 'INVALID_CSRF_TOKEN' }, 403))
      .mockResolvedValueOnce(csrf('replacement'))
      .mockResolvedValueOnce(json({ id: 'task-1', status: 'DONE', updated: true }));
    const client = createApiClient(fetchMock);

    await expect(client.updateTask('task-1', 'DONE', 'task-key')).resolves.toMatchObject({
      updated: true,
    });

    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'expired',
    });
    expect(fetchMock.mock.calls[3]?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'replacement',
      'Idempotency-Key': 'task-key',
    });
  });

  it.each(['login', 'register'] as const)(
    'keeps a successful %s session when only post-authentication CSRF priming fails',
    async (operation) => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(csrf('before-auth'))
        .mockResolvedValueOnce(json(session))
        .mockRejectedValueOnce(new TypeError('post-authentication network loss'));
      const client = createApiClient(fetchMock);

      const result = operation === 'login'
        ? client.login({ email: session.email, password: 'a-secure-passphrase' })
        : client.register({
            email: session.email,
            password: 'a-secure-passphrase',
            displayName: session.displayName,
            timeZone: 'Asia/Seoul',
          });

      await expect(result).resolves.toEqual(session);
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    },
  );

  it('does not retry a user A mutation after the session changes during CSRF refresh', async () => {
    let resolveReplacement!: (response: Response) => void;
    const replacement = new Promise<Response>((resolve) => {
      resolveReplacement = resolve;
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('user-a-expired'))
      .mockResolvedValueOnce(json({ code: 'INVALID_CSRF_TOKEN' }, 403))
      .mockReturnValueOnce(replacement);
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    const request = client.updateTask('task-1', 'DONE', 'task-key');
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    client.invalidateSession();
    client.setSessionOwner('user-b');
    resolveReplacement(csrf('user-a-replacement'));

    await expect(request).rejects.toBeInstanceOf(SessionScopeChangedError);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not retry an ordinary authorization failure', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('valid'))
      .mockResolvedValueOnce(json({ code: 'OWNER_FORBIDDEN', message: 'Forbidden' }, 403));
    const client = createApiClient(fetchMock);

    await expect(client.unlinkGoogle()).rejects.toMatchObject({
      status: 403,
      code: 'OWNER_FORBIDDEN',
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('honors a server-selected CSRF header name instead of assuming one', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json({ headerName: 'X-CUSTOM-CSRF', parameterName: 'csrf', token: 'custom-token' }),
      )
      .mockResolvedValueOnce(json(session));
    const client = createApiClient(fetchMock);

    await client.unlinkGoogle();

    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({
      'X-CUSTOM-CSRF': 'custom-token',
    });
  });

  it('completes logout without waiting for the anonymous CSRF refresh', async () => {
    let finishAnonymousRefresh!: (response: Response) => void;
    const anonymousRefresh = new Promise<Response>((resolve) => {
      finishAnonymousRefresh = resolve;
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('authenticated'))
      .mockResolvedValueOnce(noContent())
      .mockReturnValueOnce(anonymousRefresh);
    const client = createApiClient(fetchMock);

    await expect(client.logout()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/v1/auth/csrf', {
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    });

    finishAnonymousRefresh(csrf('anonymous'));
    await anonymousRefresh;
  });

  it('retains the initiating owner snapshot while a logout retry is still pending', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrf('authenticated'))
      .mockRejectedValueOnce(new TypeError('offline'));
    const client = createApiClient(fetchMock);
    client.setSessionOwner('owner-a');

    await expect(client.logout()).rejects.toThrow('offline');
    expect(client.pendingLogoutOwner()).toBe('owner-a');

    client.clearPendingLogoutIntent();
    expect(client.pendingLogoutOwner()).toBeNull();
  });
});
