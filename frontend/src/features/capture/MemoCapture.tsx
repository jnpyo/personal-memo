import type { FormEvent } from 'react';

type Props = {
  content: string;
  disabled: boolean;
  submissionDisabled: boolean;
  submitting: boolean;
  rawOnly: boolean;
  prompt: string;
  onContentChange: (content: string) => void;
  onSubmit: (content: string) => void;
};

export function MemoCapture({
  content,
  disabled,
  submissionDisabled,
  submitting,
  rawOnly,
  prompt,
  onContentChange,
  onSubmit,
}: Props) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (content.trim() && !disabled && !submissionDisabled) onSubmit(content);
  };

  return (
    <form className="capture-bar" onSubmit={handleSubmit}>
      <label htmlFor="memo-content">{prompt}</label>
      <textarea
        id="memo-content"
        value={content}
        disabled={disabled}
        required
        maxLength={20_000}
        placeholder="예: 11.25 운영체제 과제 제출"
        onChange={(event) => onContentChange(event.target.value)}
      />
      <div className="capture-actions">
        <span>{content.length.toLocaleString()} / 20,000</span>
        <button type="submit" disabled={disabled || submissionDisabled || !content.trim()}>
          {submitting
            ? rawOnly
              ? '원문 저장 중…'
              : '저장하고 분석 중…'
            : rawOnly
              ? '원문만 저장'
              : '원문 저장 후 제안 분석'}
        </button>
      </div>
    </form>
  );
}
