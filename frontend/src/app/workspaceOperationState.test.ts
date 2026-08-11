import { describe, expect, it } from 'vitest';
import {
  hasPendingServerOperation,
  isCurrentScopedRequest,
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

describe('owner-scoped graph request generation', () => {
  it.each([
    ['newer B selection started', 4, 5, false, 'tag:a', 'tag:b'],
    ['drawer closed', 5, 5, false, 'tag:a', null],
    ['request aborted', 5, 5, true, 'tag:a', 'tag:a'],
    ['older page response arrived', 4, 5, false, 'tag:a', 'tag:a'],
  ])('rejects a response after %s', (
    _label,
    request,
    latestStarted,
    aborted,
    expectedScope,
    currentScope,
  ) => {
    expect(isCurrentScopedRequest({
      request,
      latestStarted,
      aborted,
      expectedScope,
      currentScope,
    })).toBe(false);
  });

  it('allows only the latest non-aborted response for the still-open root selection', () => {
    expect(isCurrentScopedRequest({
      request: 6,
      latestStarted: 6,
      aborted: false,
      expectedScope: 'tag:b',
      currentScope: 'tag:b',
    })).toBe(true);
  });
});
