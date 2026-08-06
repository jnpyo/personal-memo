import { describe, expect, it } from 'vitest';
import { hasPendingServerOperation } from './workspaceOperationState';

describe('workspace server-operation lock', () => {
  it.each([
    ['workspace mutation', { workspaceBusy: true, pendingTaskId: null, authOperation: null }],
    ['task status mutation', { workspaceBusy: false, pendingTaskId: 'task-a', authOperation: null }],
    ['account mutation', { workspaceBusy: false, pendingTaskId: null, authOperation: 'LOGOUT' as const }],
  ])('stays locked during a pending %s', (_label, input) => {
    expect(hasPendingServerOperation(input)).toBe(true);
  });

  it('unlocks only when every server operation has settled', () => {
    expect(hasPendingServerOperation({
      workspaceBusy: false,
      pendingTaskId: null,
      authOperation: null,
    })).toBe(false);
  });
});
