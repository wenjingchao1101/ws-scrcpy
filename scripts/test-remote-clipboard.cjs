const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.join(__dirname, '../src/app/googDevice/RemoteClipboardCopy.ts'), 'utf8');
const code = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
}).outputText;

function fixture(write = async () => {}) {
    let now = 0;
    let next = 0;
    const timers = new Map();
    const exports = {};
    vm.runInNewContext(code, {
        exports,
        setTimeout: (fn, delay) => { const id = ++next; timers.set(id, { fn, at: now + delay }); return id; },
        clearTimeout: (id) => timers.delete(id),
    });
    const requests = [], states = [], writes = [];
    const sync = new exports.RemoteClipboardCopy(
        () => requests.push(now),
        async (text) => { writes.push(text); await write(text); },
        (state) => states.push(state),
    );
    const tick = async (ms) => {
        const end = now + ms;
        for (;;) {
            const due = [...timers.entries()].filter(([, timer]) => timer.at <= end).sort((a,b) => a[1].at - b[1].at)[0];
            if (!due) break;
            now = due[1].at;
            timers.delete(due[0]);
            due[1].fn();
        }
        now = end;
        for (let i=0; i<15; i++) await Promise.resolve();
    };
    return { sync, requests, states, writes, tick };
}

(async () => {
    const f = fixture();
    f.sync.start();
    assert.equal(f.requests.length, 0, 'copy must not immediately fetch stale clipboard');
    await f.tick(199);
    assert.equal(f.requests.length, 0);
    await f.tick(1);
    assert.deepEqual(f.requests, [200]);
    f.sync.receive('old');
    await f.tick(0);
    f.sync.receive('中文🙂'); // A later Android copy notification must replace the old read.
    f.sync.receive('中文🙂');
    await f.tick(0);
    assert.deepEqual(f.writes, ['old', '中文🙂']);
    await f.tick(3300);
    assert.deepEqual(f.requests, [200, 600, 1200]);
    assert.equal(f.states.includes('timeout'), false);
    f.sync.receive('unrelated');
    await f.tick(0);
    assert.equal(f.writes.length, 2, 'do not sync unrelated clipboard changes');

    const denied = fixture(async () => { throw new Error('NotAllowedError'); });
    denied.sync.start(); denied.sync.receive('text'); await denied.tick(0);
    assert.equal(denied.states.at(-1), 'blocked');

    const timeout = fixture(); timeout.sync.start(); await timeout.tick(3500);
    assert.equal(timeout.states.at(-1), 'timeout');

    const stopped = fixture(); stopped.sync.start(); stopped.sync.receive('queued'); stopped.sync.stop();
    await stopped.tick(4000);
    assert.deepEqual(stopped.requests, []);
    assert.deepEqual(stopped.writes, []);

    let release;
    const slow = fixture((text) => text === 'first' ? new Promise(resolve => { release = resolve; }) : Promise.resolve());
    slow.sync.start(); slow.sync.receive('first'); await slow.tick(0);
    slow.sync.receive('latest'); await slow.tick(0);
    assert.deepEqual(slow.writes, ['first']);
    release(); await slow.tick(0);
    assert.deepEqual(slow.writes, ['first', 'latest']);
    console.log('PASS: delayed reads, late updates, deduplication, expiry, permission failure, timeout, cancellation, write ordering');
})().catch(error => { console.error(error); process.exitCode = 1; });
