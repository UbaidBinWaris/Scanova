/** A page of a cursor-paginated list. The envelope puts `nextCursor` into `meta`. */
export class Page<Item> {
  public constructor(
    public readonly items: readonly Item[],
    public readonly nextCursor: string | null,
  ) {}
}
