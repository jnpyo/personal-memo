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
  if (error instanceof ApiError) {
    if (error.code === 'STALE_MEMO_REVISION' || error.status === 409) {
      return '메모 상태가 다른 곳에서 변경되었습니다. 최신 목록을 불러온 뒤 다시 시도해 주세요.';
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
