import { ProposalContractError } from './proposalDecoder';
import { GraphNeighborhoodContractError } from './graphNeighborhoodDecoder';
import { MemoSearchContractError } from './searchDecoder';
import { RelationReviewContractError } from './relationReviewDecoder';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type ErrorPayload = {
  code?: string;
  error?: string;
  message?: string;
};

export function errorMessage(error: unknown): string {
  if (error instanceof ProposalContractError) {
    return '서버가 지원하지 않는 형식의 분석 제안을 반환했습니다. 원본 메모는 그대로 보존됩니다.';
  }

  if (error instanceof GraphNeighborhoodContractError) {
    return '서버가 지원하지 않는 형식의 그래프 연결을 반환했습니다. 홈 그래프와 원본 메모는 변경되지 않습니다.';
  }

  if (error instanceof MemoSearchContractError) {
    return '서버가 지원하지 않는 형식의 검색 결과를 반환했습니다. 원본 메모는 변경되지 않습니다.';
  }

  if (error instanceof RelationReviewContractError) {
    return '서버가 지원하지 않는 형식의 연결 검토 정보를 반환했습니다. 선택한 제안과 원본 메모는 변경되지 않습니다.';
  }

  if (error instanceof ApiError) {
    if (error.code === 'ANALYSIS_IN_PROGRESS') {
      return '분석이 아직 진행 중입니다. 잠시 후 같은 요청으로 다시 시도해 주세요.';
    }
    if (
      error.code === 'STALE_MEMO_REVISION' ||
      error.code === 'PROPOSAL_CHANGED' ||
      error.code === 'PROPOSAL_NOT_APPLICABLE'
    ) {
      return '메모 상태가 다른 곳에서 변경되었습니다. 최신 목록을 불러온 뒤 다시 시도해 주세요.';
    }
    if (error.code === 'RELATION_SELECTION_REQUIRED') {
      return '연결 후보를 적용할지 직접 선택해 주세요. 검토 내용은 유지되었습니다.';
    }
    if (error.code === 'INVALID_RELATION_SELECTION') {
      return '연결 선택이 현재 제안과 맞지 않습니다. 검토 내용은 유지되었습니다.';
    }
    if (error.code === 'RELATION_SOURCE_NOT_APPLIED') {
      return '연결의 출발 항목이 생성 목록에 없습니다. 항목과 연결 선택을 확인해 주세요.';
    }
    if (error.code === 'RELATION_TARGET_UNAVAILABLE') {
      return '선택한 연결 대상을 더 이상 사용할 수 없습니다. 검토 내용은 유지되었습니다.';
    }
    if (error.status === 409) {
      return '요청이 현재 상태와 충돌했습니다. 입력한 검토 내용은 유지되었습니다.';
    }
    if (error.status === 404) {
      return '요청한 데이터를 찾을 수 없습니다.';
    }
    return error.message;
  }

  if (error instanceof TypeError) {
    return '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.';
  }

  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
}

export async function toApiError(response: Response): Promise<ApiError> {
  let payload: ErrorPayload | undefined;

  try {
    payload = (await response.json()) as ErrorPayload;
  } catch {
    payload = undefined;
  }

  const code = payload?.code ?? payload?.error;
  const message = payload?.message ?? code ?? `요청이 실패했습니다. (${response.status})`;
  return new ApiError(message, response.status, code, payload);
}
