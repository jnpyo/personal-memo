import type { CSSProperties, FormEvent } from 'react';

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
      <label htmlFor="memo-content" style={visuallyHiddenLabelStyle}>
        메모 내용
      </label>
      {prompt.trim() && (
        <p className="capture-prompt" role="status">
          {prompt}
        </p>
      )}
      <textarea
        id="memo-content"
        value={content}
        disabled={disabled}
        required
        maxLength={20_000}
        placeholder="무슨 생각이 떠올랐나요?"
        onChange={(event) => onContentChange(event.target.value)}
      />
      <div className="capture-actions">
        <span>{content.length.toLocaleString()} / 20,000</span>
        <button type="submit" disabled={disabled || submissionDisabled || !content.trim()}>
          {submitting ? '저장 중…' : '저장'}
        </button>
      </div>
    </form>
  );
}

const visuallyHiddenLabelStyle: CSSProperties = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
};
