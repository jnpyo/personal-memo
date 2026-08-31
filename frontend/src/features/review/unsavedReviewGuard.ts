export const UNSAVED_REVIEW_NAVIGATION_MESSAGE =
  '승인되지 않은 제안 수정 내용은 아직 저장되지 않았습니다. 이 화면을 떠나면 수정 내용이 사라집니다. 계속할까요?';

export const UNSAVED_REVIEW_POSTPONE_MESSAGE =
  '수정한 제안 내용은 보류 상태에 저장되지 않습니다. 다음에 원래 제안부터 다시 검토하게 됩니다. 계속할까요?';

export const UNSAVED_WORKSPACE_NAVIGATION_MESSAGE =
  '서버에 아직 반영되지 않은 편집 내용이 있습니다. 이 화면을 떠나면 해당 내용이 사라질 수 있습니다. 계속할까요?';

export const SOURCE_CHANGE_DISCARDS_REVIEW_MESSAGE =
  '원문 상태를 바꾸면 현재 제안 수정 내용이 사라지고 해당 제안이 오래된 상태가 될 수 있습니다. 계속할까요?';

export function hasUnsavedWorkspaceChanges(input: {
  reviewEdited: boolean;
  transientReviewInput: boolean;
  memoEdit: boolean;
  unpersistedCapture: boolean;
  calendarSharingProtected?: boolean;
}): boolean {
  return input.reviewEdited ||
    input.transientReviewInput ||
    input.memoEdit ||
    input.unpersistedCapture ||
    input.calendarSharingProtected === true;
}

export function confirmReviewDiscard(
  hasUnsavedReview: boolean,
  message: string,
  confirm: (message: string) => boolean = window.confirm,
): boolean {
  return !hasUnsavedReview || confirm(message);
}
