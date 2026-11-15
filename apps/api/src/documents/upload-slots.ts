/**
 * Bounds how many uploads run at once, per user and in total.
 *
 * Each running upload holds a multipart part buffer (about 8 MiB) plus stream
 * state, so without a bound a handful of stalled uploads could exhaust the API's
 * memory cap. Slots are in-process: the API runs as a single process.
 */
export class UploadSlots {
  private readonly perUserActive = new Map<number, number>();
  private totalActive = 0;

  public constructor(
    private readonly maxPerUser: number,
    private readonly maxTotal: number,
  ) {}

  /** Takes a slot for `userId`, or returns `null` when the user or the server is at the limit. */
  public tryAcquire(userId: number): (() => void) | null {
    const mine = this.perUserActive.get(userId) ?? 0;
    if (mine >= this.maxPerUser || this.totalActive >= this.maxTotal) {
      return null;
    }
    this.perUserActive.set(userId, mine + 1);
    this.totalActive += 1;
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.totalActive -= 1;
      const left = (this.perUserActive.get(userId) ?? 1) - 1;
      if (left <= 0) {
        this.perUserActive.delete(userId);
      } else {
        this.perUserActive.set(userId, left);
      }
    };
  }
}
