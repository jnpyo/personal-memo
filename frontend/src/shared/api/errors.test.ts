import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage } from './errors';

describe('API error messages', () => {
  it('distinguishes a recoverable in-progress analysis from a stale memo conflict', () => {
    expect(errorMessage(new ApiError('internal', 409, 'ANALYSIS_IN_PROGRESS'))).toBe(
      '분석이 아직 진행 중입니다. 잠시 후 같은 요청으로 다시 시도해 주세요.',
    );
    expect(errorMessage(new ApiError('internal', 409, 'STALE_MEMO_REVISION'))).toBe(
      '메모 상태가 다른 곳에서 변경되었습니다. 최신 목록을 불러온 뒤 다시 시도해 주세요.',
    );
  });
});
