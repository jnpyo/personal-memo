import { describe, expect, it, vi } from 'vitest';
import {
  AuthTransitionChannel,
  type AuthTransitionSignal,
  type AuthTransitionTransport,
} from './authTransitionChannel';

class FakeTransport implements AuthTransitionTransport {
  published: AuthTransitionSignal[] = [];
  private listener: ((signal: unknown) => void) | null = null;

  publish(signal: AuthTransitionSignal): void {
    this.published.push(signal);
  }

  subscribe(listener: (signal: unknown) => void): () => void {
    this.listener = listener;
    return () => {
      this.listener = null;
    };
  }

  deliver(signal: unknown): void {
    this.listener?.(signal);
  }
}

describe('AuthTransitionChannel', () => {
  it('publishes only a non-sensitive transition envelope', () => {
    const transport = new FakeTransport();
    const channel = new AuthTransitionChannel(transport, vi.fn(), () => 'signal-1', () => 42);

    channel.publish('AUTHENTICATED');

    expect(transport.published).toEqual([{
      version: 1,
      id: 'signal-1',
      reason: 'AUTHENTICATED',
      emittedAt: 42,
    }]);
    expect(JSON.stringify(transport.published)).not.toMatch(/owner|email|userId|token/i);
  });

  it('broadcasts a logout-pending transition without owner or memo data', () => {
    const transport = new FakeTransport();
    const channel = new AuthTransitionChannel(transport, vi.fn(), () => 'logout-1', () => 43);

    channel.publish('LOGOUT_PENDING');

    expect(transport.published).toEqual([{
      version: 1,
      id: 'logout-1',
      reason: 'LOGOUT_PENDING',
      emittedAt: 43,
    }]);
    expect(JSON.stringify(transport.published)).not.toMatch(/owner|email|userId|memo|token/i);
  });

  it('deduplicates received messages and ignores its own echoed publication', () => {
    const transport = new FakeTransport();
    const listener = vi.fn();
    const channel = new AuthTransitionChannel(transport, listener, () => 'local', () => 42);
    channel.publish('LOGGED_OUT');

    transport.deliver(transport.published[0]);
    const remote = {
      version: 1,
      id: 'remote',
      reason: 'SESSION_EXPIRED',
      emittedAt: 43,
    } satisfies AuthTransitionSignal;
    transport.deliver(remote);
    transport.deliver(remote);

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(remote);
  });

  it('ignores malformed cross-tab values and stops after close', () => {
    const transport = new FakeTransport();
    const listener = vi.fn();
    const channel = new AuthTransitionChannel(transport, listener);

    transport.deliver({ version: 1, id: 'bad', reason: 'UNKNOWN', emittedAt: 1 });
    channel.close();
    transport.deliver({
      version: 1,
      id: 'late',
      reason: 'LOGGED_OUT',
      emittedAt: 2,
    });

    expect(listener).not.toHaveBeenCalled();
  });
});
