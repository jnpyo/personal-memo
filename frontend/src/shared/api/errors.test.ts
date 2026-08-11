import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage } from './errors';
import { GraphNeighborhoodContractError } from './graphNeighborhoodDecoder';

describe('API error messages', () => {
  it('distinguishes a recoverable in-progress analysis from a stale memo conflict', () => {
    expect(errorMessage(new ApiError('internal', 409, 'ANALYSIS_IN_PROGRESS'))).toBe(
      '분석이 아직 진행 중입니다. 잠시 후 같은 요청으로 다시 시도해 주세요.',
    );
    expect(errorMessage(new ApiError('internal', 409, 'STALE_MEMO_REVISION'))).toBe(
      '메모 상태가 다른 곳에서 변경되었습니다. 최신 목록을 불러온 뒤 다시 시도해 주세요.',
    );
  });

  it('reports malformed graph data without implying that canonical data changed', () => {
    expect(errorMessage(new GraphNeighborhoodContractError('neighbors[0]'))).toBe(
      '서버가 지원하지 않는 형식의 그래프 연결을 반환했습니다. 홈 그래프와 원본 메모는 변경되지 않습니다.',
    );
  });
});
