export type ConnectionState = 'checking' | 'online' | 'offline';

export const OFFLINE_CAPTURE_PROMPT =
  '서버 연결이 끊겨 있습니다. 이 입력은 기기에 저장되지 않으며, 서버에 다시 연결하기 전에는 제출할 수 없습니다.';

export function canSubmitMemo(connection: ConnectionState): boolean {
  return connection === 'online';
}
