import { describe, expect, it } from 'vitest';
import {
  hasPendingServerOperation,
  isLatestWorkspaceRequest,
} from './workspaceOperationState';

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

describe('workspace refresh generation', () => {
  it('allows only the latest-started request to commit data, error, or loading state', () => {
    expect(isLatestWorkspaceRequest(4, 5)).toBe(false);
    expect(isLatestWorkspaceRequest(5, 5)).toBe(true);
  });
});
