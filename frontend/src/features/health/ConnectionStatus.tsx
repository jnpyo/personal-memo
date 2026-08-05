type Props = {
  status: 'checking' | 'online' | 'offline';
  onRetry: () => void;
};

const LABEL = {
  checking: '서버 확인 중',
  online: '서버 연결됨',
  offline: '서버 연결 필요',
};

export function ConnectionStatus({ status, onRetry }: Props) {
  return (
    <button
      type="button"
      className={`connection-status connection-status--${status}`}
      onClick={status === 'offline' ? onRetry : undefined}
      aria-label={status === 'offline' ? '서버 연결 다시 확인' : undefined}
    >
      <span aria-hidden="true" />
      {LABEL[status]}
    </button>
  );
}
