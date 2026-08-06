const RAW_MEMO_DRAFT_KEY_PREFIX = 'personal-memo.raw-memo-draft.v1:';

export type RawMemoDraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

type StorageProvider = () => RawMemoDraftStorage | null;

function browserStorage(): RawMemoDraftStorage | null {
  if (typeof window === 'undefined') return null;
  return window.localStorage;
}

function ownerDraftKey(ownerId: string): string | null {
  const normalizedOwnerId = ownerId.trim();
  if (!normalizedOwnerId) return null;
  return `${RAW_MEMO_DRAFT_KEY_PREFIX}${encodeURIComponent(normalizedOwnerId)}`;
}

/**
 * Stores one bounded raw-text draft per authenticated owner.
 *
 * This deliberately uses a synchronous storage boundary instead of IndexedDB: the capture is a
 * single textarea value (not an offline outbox), and every input event must finish persisting
 * before a logout or OAuth navigation can unload the page.
 */
export class RawMemoDraftStore {
  constructor(private readonly storageProvider: StorageProvider = browserStorage) {}

  read(ownerId: string): string {
    const key = ownerDraftKey(ownerId);
    if (!key) return '';
    try {
      return this.storageProvider()?.getItem(key) ?? '';
    } catch {
      return '';
    }
  }

  save(ownerId: string, content: string): boolean {
    const key = ownerDraftKey(ownerId);
    if (!key) return false;
    try {
      const storage = this.storageProvider();
      if (!storage) return false;
      if (content === '') {
        storage.removeItem(key);
      } else {
        storage.setItem(key, content);
      }
      return true;
    } catch {
      return false;
    }
  }

  clear(ownerId: string): boolean {
    const key = ownerDraftKey(ownerId);
    if (!key) return false;
    try {
      const storage = this.storageProvider();
      if (!storage) return false;
      storage.removeItem(key);
      return true;
    } catch {
      return false;
    }
  }
}

export const rawMemoDraftStore = new RawMemoDraftStore();
