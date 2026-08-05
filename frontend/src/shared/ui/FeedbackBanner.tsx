export type Feedback = {
  kind: 'info' | 'success' | 'error';
  message: string;
};

type Props = {
  feedback: Feedback | null;
  retryLabel?: string;
  onRetry?: () => void;
  onDismiss: () => void;
};

export function FeedbackBanner({ feedback, retryLabel, onRetry, onDismiss }: Props) {
  if (!feedback) return null;

  return (
    <div className={`feedback feedback--${feedback.kind}`} role={feedback.kind === 'error' ? 'alert' : 'status'}>
      <p>{feedback.message}</p>
      <div>
        {onRetry && (
          <button type="button" onClick={onRetry}>
            {retryLabel ?? '다시 시도'}
          </button>
        )}
        <button type="button" aria-label="알림 닫기" onClick={onDismiss}>
          닫기
        </button>
      </div>
    </div>
  );
}
