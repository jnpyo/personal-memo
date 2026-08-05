import type { MemoView, UpdateMemoRequest } from '../../shared/api/types';

const ANALYSIS_LABELS: Record<string, string> = {
  NOT_STARTED: '분석 전',
  REVIEW_REQUIRED: '제안 검토 필요',
  POSTPONED: '제안 보류',
  REJECTED: '제안 거절',
  APPLIED: '승인 내용 적용됨',
  UNDONE: '적용 되돌림',
  STALE: '이전 revision 분석',
};

export function analysisStateLabel(state: string): string {
  return ANALYSIS_LABELS[state] ?? state;
}

export function analysisStateTone(state: string): 'neutral' | 'attention' | 'complete' {
  if (state === 'REVIEW_REQUIRED' || state === 'POSTPONED' || state === 'STALE') {
    return 'attention';
  }
  if (state === 'APPLIED') {
    return 'complete';
  }
  return 'neutral';
}

export function buildUpdateMemoRequest(memo: MemoView, content: string): UpdateMemoRequest {
  return {
    expectedRevision: memo.currentRevision,
    content,
  };
}

export function isMemoContentValid(content: string): boolean {
  return content.trim().length > 0 && content.length <= 20_000;
}
