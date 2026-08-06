/**
 * Synchronously excludes overlapping authentication operations. React state
 * updates are intentionally not used as the lock because two submit events can
 * arrive before the pending state is rendered.
 */
export class AuthOperationGate {
  private generation = 0;
  private activeToken: number | null = null;

  get isActive(): boolean {
    return this.activeToken !== null;
  }

  begin(): number | null {
    if (this.activeToken !== null) return null;
    const token = ++this.generation;
    this.activeToken = token;
    return token;
  }

  end(token: number): void {
    if (this.activeToken === token) this.activeToken = null;
  }

  cancel(): void {
    this.generation += 1;
    this.activeToken = null;
  }
}
