import { useMemo } from 'react';
import type { ItemKind } from '../../shared/api/types';
import {
  captureDateInTimeZone,
  resolveWallClockInTimeZone,
  toTwentyFourHour,
  type BareTimeClarification as BareTimeClarificationValue,
} from './bareTimeClarificationModel';

type Props = {
  clarification: BareTimeClarificationValue;
  itemKind: Extract<ItemKind, 'TASK' | 'EVENT'>;
  sourceTimeZone: string | null;
  clientRecordedAt: string | null;
  disabled: boolean;
  draft: BareTimeClarificationDraft;
  onDraftChange: (draft: BareTimeClarificationDraft) => void;
  onConfirm: (offsetDateTime: string) => void;
  onDismiss: () => void;
};

export type BareTimeClarificationDraft = {
  date: string;
  period: '' | 'AM' | 'PM';
  overlapValue: string;
};

export const EMPTY_BARE_TIME_CLARIFICATION_DRAFT: BareTimeClarificationDraft = {
  date: '',
  period: '',
  overlapValue: '',
};

function clockLabel(hour12: number, minute: number): string {
  return `${hour12}:${String(minute).padStart(2, '0')}`;
}

export function BareTimeClarification({
  clarification,
  itemKind,
  sourceTimeZone,
  clientRecordedAt,
  disabled,
  draft,
  onDraftChange,
  onConfirm,
  onDismiss,
}: Props) {
  const { date, period, overlapValue } = draft;
  const captureDate = sourceTimeZone && clientRecordedAt
    ? captureDateInTimeZone(clientRecordedAt, sourceTimeZone)
    : null;
  const selectedHour = clarification.fixedHour24 ?? (
    period ? toTwentyFourHour(clarification.hour12, period) : null
  );
  const resolution = useMemo(() => {
    if (!date || selectedHour === null || !sourceTimeZone) return null;
    return resolveWallClockInTimeZone(
      date,
      selectedHour,
      clarification.minute,
      sourceTimeZone,
    );
  }, [clarification.minute, date, selectedHour, sourceTimeZone]);
  const resolvedValue = resolution?.status === 'UNIQUE'
    ? resolution.option.value
    : resolution?.status === 'OVERLAP'
      ? overlapValue
      : '';

  const chooseDate = (nextDate: string) => {
    onDraftChange({ ...draft, date: nextDate, overlapValue: '' });
  };
  const choosePeriod = (nextPeriod: Exclude<BareTimeClarificationDraft['period'], ''>) => {
    onDraftChange({ ...draft, period: nextPeriod, overlapValue: '' });
  };
  const resetAndRun = (action: () => void) => {
    onDraftChange(EMPTY_BARE_TIME_CLARIFICATION_DRAFT);
    action();
  };
  const clock = clockLabel(clarification.hour12, clarification.minute);
  const fixedClock = clarification.fixedHour24 === null
    ? null
    : clarification.fixedPeriod === 'AM'
      ? `오전 ${clock}`
      : clarification.fixedPeriod === 'PM'
        ? `오후 ${clock}`
        : `${String(clarification.fixedHour24).padStart(2, '0')}:${String(
            clarification.minute,
          ).padStart(2, '0')}`;
  const helpId = `bare-time-${clarification.itemIndex}-help`;

  return (
    <section className="bare-time-clarification" aria-describedby={helpId}>
      <div className="bare-time-clarification__heading">
        <strong>‘{clarification.candidate.surfaceText}’를 정확히 지정</strong>
        <span>
          {fixedClock === null ? '날짜와 오전·오후를 선택하세요.' : '날짜를 선택하세요.'}
        </span>
      </div>

      <div className="bare-time-clarification__date">
        <label>
          날짜
          <input
            type="date"
            value={date}
            disabled={disabled}
            aria-label={`${clarification.candidate.surfaceText} 날짜`}
            onChange={(event) => chooseDate(event.target.value)}
          />
        </label>
        <button
          type="button"
          className="secondary-button"
          disabled={disabled || captureDate === null}
          onClick={() => {
            if (captureDate) chooseDate(captureDate);
          }}
        >
          작성일
        </button>
      </div>

      {fixedClock === null ? (
        <div className="bare-time-clarification__period" role="group" aria-label="오전 또는 오후">
          <button
            type="button"
            className={period === 'AM' ? 'choice-button choice-button--selected' : 'choice-button'}
            aria-pressed={period === 'AM'}
            disabled={disabled}
            onClick={() => choosePeriod('AM')}
          >
            오전 {clock}
          </button>
          <button
            type="button"
            className={period === 'PM' ? 'choice-button choice-button--selected' : 'choice-button'}
            aria-pressed={period === 'PM'}
            disabled={disabled}
            onClick={() => choosePeriod('PM')}
          >
            오후 {clock}
          </button>
        </div>
      ) : (
        <p className="field-help">원문 시각 {fixedClock}</p>
      )}

      {resolution?.status === 'OVERLAP' && (
        <fieldset className="bare-time-clarification__overlap">
          <legend>시간대 전환으로 같은 시각이 두 번 있습니다.</legend>
          {resolution.options.map((option, index) => (
            <label key={option.value}>
              <input
                type="radio"
                name={`bare-time-overlap-${clarification.itemIndex}`}
                value={option.value}
                checked={overlapValue === option.value}
                disabled={disabled}
                onChange={() => onDraftChange({ ...draft, overlapValue: option.value })}
              />
              {index === 0 ? '먼저 오는 시각' : '나중에 오는 시각'} ({option.offset})
            </label>
          ))}
        </fieldset>
      )}

      <p
        id={helpId}
        className={resolution?.status === 'GAP' || resolution?.status === 'INVALID_ZONE'
          ? 'field-error'
          : 'field-help'}
        role={resolution?.status === 'GAP' || resolution?.status === 'INVALID_ZONE'
          ? 'alert'
          : undefined}
      >
        {!sourceTimeZone || !clientRecordedAt
          ? '메모를 작성한 시각과 시간대를 확인한 뒤 시각을 지정할 수 있습니다.'
          : resolution?.status === 'GAP'
            ? '이 현지 시각은 시간대 전환으로 존재하지 않습니다. 다른 날짜를 선택해 주세요.'
            : resolution?.status === 'OVERLAP' && !overlapValue
              ? '둘 중 실제로 사용할 시각을 선택해 주세요.'
              : resolution?.status === 'INVALID_ZONE'
                ? '메모 시간대를 확인할 수 없습니다.'
                : sourceTimeZone}
      </p>

      <div className="bare-time-clarification__actions">
        <button
          type="button"
          className="secondary-button"
          disabled={disabled || !resolvedValue}
          onClick={() => resetAndRun(() => onConfirm(resolvedValue))}
        >
          {itemKind === 'TASK' ? '마감 시각으로 사용' : '일정 시작으로 사용'}
        </button>
        <button
          type="button"
          className="text-button"
          disabled={disabled}
          onClick={() => resetAndRun(onDismiss)}
        >
          시간 없이 두기
        </button>
      </div>
    </section>
  );
}
