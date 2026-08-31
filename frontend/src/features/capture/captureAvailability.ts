export type ConnectionState = 'checking' | 'online' | 'offline';

export const OFFLINE_CAPTURE_PROMPT =
  '서버 연결이 끊겨 있습니다. 입력은 이 계정 전용 임시 초안으로 기기에 저장되지만, 서버에 다시 연결하기 전에는 제출할 수 없습니다.';

export const LOCAL_DRAFT_STORAGE_FAILED_PROMPT =
  '브라우저 저장소에 임시 초안을 보존하지 못했습니다. 이 화면을 닫지 말고 서버에 연결한 뒤 원문을 제출해 주세요.';

export function canSubmitMemo(connection: ConnectionState): boolean {
  return connection === 'online';
}
