import { describe, expect, it, vi } from 'vitest';
import {
  confirmReviewDiscard,
  hasUnsavedWorkspaceChanges,
  SOURCE_CHANGE_DISCARDS_REVIEW_MESSAGE,
  UNSAVED_REVIEW_NAVIGATION_MESSAGE,
  UNSAVED_REVIEW_POSTPONE_MESSAGE,
  UNSAVED_WORKSPACE_NAVIGATION_MESSAGE,
} from './unsavedReviewGuard';

describe('unsaved review guard', () => {
  it('allows navigation without prompting when the review is unchanged', () => {
    const confirm = vi.fn();

    expect(confirmReviewDiscard(false, UNSAVED_REVIEW_NAVIGATION_MESSAGE, confirm)).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('uses the supplied warning and respects cancellation for edited reviews', () => {
    const confirm = vi.fn(() => false);

    expect(confirmReviewDiscard(true, UNSAVED_REVIEW_POSTPONE_MESSAGE, confirm)).toBe(false);
    expect(confirm).toHaveBeenCalledWith(UNSAVED_REVIEW_POSTPONE_MESSAGE);
  });

  it('continues only after the user confirms discarding edits', () => {
    const confirm = vi.fn(() => true);

    expect(confirmReviewDiscard(true, UNSAVED_REVIEW_NAVIGATION_MESSAGE, confirm)).toBe(true);
  });

  it('keeps navigation and source-change warnings distinct', () => {
    expect(UNSAVED_WORKSPACE_NAVIGATION_MESSAGE).toContain('편집 내용');
    expect(SOURCE_CHANGE_DISCARDS_REVIEW_MESSAGE).toContain('원문 상태');
  });

  it.each([
    ['review edit', { reviewEdited: true, transientReviewInput: false, memoEdit: false, unpersistedCapture: false }],
    ['uncommitted tag input', { reviewEdited: false, transientReviewInput: true, memoEdit: false, unpersistedCapture: false }],
    ['memo edit', { reviewEdited: false, transientReviewInput: false, memoEdit: true, unpersistedCapture: false }],
    ['blocked local draft', { reviewEdited: false, transientReviewInput: false, memoEdit: false, unpersistedCapture: true }],
  ])('includes %s in the unified workspace guard', (_label, input) => {
    expect(hasUnsavedWorkspaceChanges(input)).toBe(true);
  });

  it('stays clear after every local edit has been resolved', () => {
    expect(hasUnsavedWorkspaceChanges({
      reviewEdited: false,
      transientReviewInput: false,
      memoEdit: false,
      unpersistedCapture: false,
    })).toBe(false);
  });
});
