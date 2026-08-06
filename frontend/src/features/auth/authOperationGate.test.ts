import { describe, expect, it } from 'vitest';
import { AuthOperationGate } from './authOperationGate';

describe('AuthOperationGate', () => {
  it('rejects a duplicate submission synchronously', () => {
    const gate = new AuthOperationGate();

    const first = gate.begin();
    const duplicate = gate.begin();

    expect(first).not.toBeNull();
    expect(duplicate).toBeNull();
  });

  it('does not let a canceled old operation release a newer operation', () => {
    const gate = new AuthOperationGate();
    const oldToken = gate.begin()!;
    gate.cancel();
    const newToken = gate.begin()!;

    gate.end(oldToken);

    expect(gate.isActive).toBe(true);
    expect(gate.begin()).toBeNull();
    gate.end(newToken);
    expect(gate.isActive).toBe(false);
  });
});
