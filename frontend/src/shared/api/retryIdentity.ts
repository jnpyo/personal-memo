export type CaptureAttempt = {
  content: string;
  memoId: string;
  clientCreatedAt: string;
  createKey: string;
  analysisKey: string;
};

type IdentityFactory = () => string;
type Clock = () => Date;

export function createCaptureAttempt(
  content: string,
  idFactory: IdentityFactory = () => crypto.randomUUID(),
  clock: Clock = () => new Date(),
): CaptureAttempt {
  return {
    content,
    memoId: idFactory(),
    clientCreatedAt: clock().toISOString(),
    createKey: idFactory(),
    analysisKey: idFactory(),
  };
}

export class RetryIdentityStore {
  private readonly entries = new Map<string, { fingerprint: string; key: string }>();

  constructor(private readonly idFactory: IdentityFactory = () => crypto.randomUUID()) {}

  keyFor(scope: string, fingerprint: string): string {
    const existing = this.entries.get(scope);
    if (existing?.fingerprint === fingerprint) {
      return existing.key;
    }

    const key = this.idFactory();
    this.entries.set(scope, { fingerprint, key });
    return key;
  }

  clear(scope: string): void {
    this.entries.delete(scope);
  }
}
