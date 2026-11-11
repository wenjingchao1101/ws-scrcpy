export type CopyState = 'waiting' | 'copied' | 'blocked' | 'timeout';

/** Clipboard replies have no request IDs. Keep accepting updates during the copy window. */
export class RemoteClipboardCopy {
    private generation = 0;
    private active = false;
    private received = false;
    private latest?: string;
    private timers: Array<ReturnType<typeof setTimeout>> = [];
    private writes: Promise<void> = Promise.resolve();

    constructor(
        private readonly request: () => void,
        private readonly write: (text: string) => Promise<void>,
        private readonly report: (state: CopyState) => void,
    ) {}

    public start(): void {
        this.stop();
        this.active = true;
        this.received = false;
        this.latest = undefined;
        this.report('waiting');
        // Android dispatches the copy key asynchronously. Do not immediately read stale data.
        for (const delay of [200, 600, 1200]) {
            this.timers.push(setTimeout(this.request, delay));
        }
        this.timers.push(setTimeout(() => {
            this.active = false;
            if (!this.received) {
                this.report('timeout');
            }
        }, 3500));
    }

    public receive(text: string): void {
        if (!this.active || (this.received && text === this.latest)) {
            return;
        }
        this.received = true;
        this.latest = text;
        const generation = this.generation;
        // Serialize writes so a slow earlier reply cannot overwrite a newer value.
        this.writes = this.writes.then(async () => {
            if (generation !== this.generation) {
                return;
            }
            try {
                await this.write(text);
                if (generation === this.generation) {
                    this.report('copied');
                }
            } catch {
                if (generation === this.generation) {
                    this.report('blocked');
                }
            }
        });
    }

    public stop(): void {
        this.generation++;
        this.active = false;
        this.timers.forEach(clearTimeout);
        this.timers = [];
    }
}
