type Props = {
  title: string;
  onResume: () => void;
};

export function PostponedReview({ title, onResume }: Props) {
  return (
    <aside className="postponed-card">
      <div>
        <span className="eyebrow">POSTPONED</span>
        <p>“{title}” 제안을 보류했습니다. 아직 어떤 항목도 생성되지 않았습니다.</p>
      </div>
      <button type="button" className="secondary-button" onClick={onResume}>
        검토 계속하기
      </button>
    </aside>
  );
}
