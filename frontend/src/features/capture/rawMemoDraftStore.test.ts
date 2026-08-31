import { describe, expect, it } from 'vitest';
import { RawMemoDraftStore, type RawMemoDraftStorage } from './rawMemoDraftStore';

class MemoryStorage implements RawMemoDraftStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }
}

describe('owner-scoped raw memo draft storage', () => {
  it('persists textarea input synchronously for the same owner', () => {
    const storage = new MemoryStorage();
    const drafts = new RawMemoDraftStore(() => storage);

    expect(drafts.save('owner-a', '로그아웃 전에 보존할 원문')).toBe(true);
    expect(drafts.read('owner-a')).toBe('로그아웃 전에 보존할 원문');
  });

  it('never exposes one owner draft through another owner key', () => {
    const storage = new MemoryStorage();
    const drafts = new RawMemoDraftStore(() => storage);

    drafts.save('owner-a', 'A의 비공개 메모');
    drafts.save('owner-b', 'B의 비공개 메모');

    expect(drafts.read('owner-a')).toBe('A의 비공개 메모');
    expect(drafts.read('owner-b')).toBe('B의 비공개 메모');
    expect(drafts.read('owner-c')).toBe('');
  });

  it('removes only the successful owner draft', () => {
    const storage = new MemoryStorage();
    const drafts = new RawMemoDraftStore(() => storage);
    drafts.save('owner-a', '서버 저장 완료');
    drafts.save('owner-b', '아직 작성 중');

    expect(drafts.clear('owner-a')).toBe(true);

    expect(drafts.read('owner-a')).toBe('');
    expect(drafts.read('owner-b')).toBe('아직 작성 중');
  });

  it('treats an empty textarea as clearing that owner draft', () => {
    const storage = new MemoryStorage();
    const drafts = new RawMemoDraftStore(() => storage);
    drafts.save('owner-a', '지울 초안');

    expect(drafts.save('owner-a', '')).toBe(true);
    expect(drafts.read('owner-a')).toBe('');
  });

  it('fails closed when browser storage is unavailable', () => {
    const drafts = new RawMemoDraftStore(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    expect(drafts.read('owner-a')).toBe('');
    expect(drafts.save('owner-a', '민감한 원문')).toBe(false);
    expect(drafts.clear('owner-a')).toBe(false);
  });
});
