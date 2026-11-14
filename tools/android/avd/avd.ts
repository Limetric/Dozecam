#!/usr/bin/env bun
// Keeps the disk use of the Android emulators (AVDs) in check. An AVD's disk
// images only grow: files deleted inside the emulator don't shrink them, and
// every Quick Boot save keeps a snapshot inside the image that holds on to the
// data written since, which let a 12 GB data partition take 20 GB on disk.
// android/AGENTS.md says when to run which command.

import { dlopen, FFIType } from "bun:ffi";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** The data partition a wipe caps an AVD at: room for the ~350 MB debug APK. */
export const DEFAULT_DATA_SIZE = "6G";
export const DEFAULT_PRUNE_DAYS = 3;
/** AVDs an agent makes for one task, which prune deletes once unused. */
export const TEMP_PREFIX = "dz-tmp-";
/** A compaction smaller than this isn't worth rewriting the image for. */
const MIN_COMPACT_GAIN = 256 * 1024 * 1024;

export const COLD_BOOT: Record<string, string> = {
  "fastboot.forceColdBoot": "yes",
  "fastboot.forceFastBoot": "no",
  "fastboot.forceChosenSnapshotBoot": "no",
};

// What the emulator's own -wipe-data recreates on the next boot, from the
// system image.
const WIPE_FILES = [
  "userdata-qemu.img",
  "userdata-qemu.img.qcow2",
  "cache.img",
  "cache.img.qcow2",
  "encryptionkey.img",
  "encryptionkey.img.qcow2",
  "snapshots",
];

const QUICK_BOOT_SNAPSHOT = "default_boot";
const LAUNCH_AGENT_LABEL = "app.dozecam.avd-reclaim";

const USAGE = `usage: bun tools/android/avd/avd.ts [--dry-run] <command> [args]

  status [AVD...]            disk use, state and boot mode of each AVD
  configure AVD...           boot cold, so the emulator stops saving Quick Boot
                             snapshots; takes effect on the next boot
  compact [--force] AVD...   drop the Quick Boot snapshots of stopped AVDs and
                             rewrite their disk images without the space those
                             held; keeps the data
  wipe [--data-size SIZE] AVD...
                             factory-reset stopped AVDs, boot them cold and cap
                             their data partition (default ${DEFAULT_DATA_SIZE})
  prune [--days N]           delete stopped ${TEMP_PREFIX}* AVDs unused for N days
                             (default ${DEFAULT_PRUNE_DAYS})
  auto [AVD...]              prune, then configure and compact Dozecam_*,
                             ${TEMP_PREFIX}* and the named AVDs: what the schedule runs
  schedule install [AVD...]  run auto weekly (macOS), also on the named AVDs
  schedule remove            stop the weekly run

AVDs that are running are skipped: stop them first (adb -s <serial> emu kill).
`;

export type Avd = { name: string; dir: string; ini: string };

export class UsageError extends Error {}

export function avdHome(env = process.env): string {
  if (env.ANDROID_AVD_HOME) return env.ANDROID_AVD_HOME;
  if (env.ANDROID_EMULATOR_HOME) return path.join(env.ANDROID_EMULATOR_HOME, "avd");
  if (env.ANDROID_USER_HOME) return path.join(env.ANDROID_USER_HOME, "avd");
  return path.join(os.homedir(), ".android", "avd");
}

export function sdkRoot(env = process.env): string {
  return (
    env.ANDROID_HOME ||
    env.ANDROID_SDK_ROOT ||
    path.join(os.homedir(), process.platform === "darwin" ? "Library/Android/sdk" : "Android/Sdk")
  );
}

export function readIni(text: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf("=");
    if (i > 0) values[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return values;
}

/**
 * Sets keys in an ini file's text, keeping every other line as it was. Keeps
 * the file's own `key=value` or `key = value` style: the emulator rewrites
 * config.ini in the second on boot.
 */
export function setIni(text: string, values: Record<string, string>): string {
  const lines = text.split(/\r?\n/);
  if (lines.at(-1) === "") lines.pop();
  const written = new Set<string>();
  const out = lines.map((line) => {
    const m = /^(\s*([^=]*?)\s*=\s*)(.*?)\s*$/.exec(line);
    if (!m || !(m[2] in values)) return line;
    written.add(m[2]);
    return m[1] + values[m[2]];
  });
  const sep = lines.some((line) => line.includes(" = ")) ? " = " : "=";
  for (const [key, value] of Object.entries(values)) {
    if (!written.has(key)) out.push(`${key}${sep}${value}`);
  }
  return out.join("\n") + "\n";
}

/** Parses a size the way config.ini writes it: bytes, or with a K/M/G/T suffix. */
export function parseSize(size: string): number {
  const m = /^\s*(\d+(?:\.\d+)?)\s*([KMGT]?)B?\s*$/i.exec(size);
  if (!m) return Number.NaN;
  const exp = " KMGT".indexOf(m[2].toUpperCase() || " ");
  return Math.round(Number(m[1]) * 1024 ** exp);
}

export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (Math.abs(value) >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
}

export type Process = { pid: string; comm: string; args: string };

/**
 * The AVDs an emulator is running, by its -avd or @ argument. Only the
 * emulator's own processes count, by executable: a shell or script whose
 * command line names the AVD would otherwise look like a running emulator.
 */
export function runningAvds(processes: Process[], names: string[]): Set<string> {
  const running = new Set<string>();
  for (const { comm, args } of processes) {
    if (!/^(emulator|qemu-system)/.test(path.basename(comm))) continue;
    for (const name of names) {
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      if (new RegExp(`(?:^|\\s)(?:-avd\\s+|@)${escaped}(?:\\s|$)`).test(args)) running.add(name);
    }
  }
  return running;
}

/** Joins ps output listing "pid comm" with ps output listing "pid args". */
export function parseProcesses(commOutput: string, argsOutput: string): Process[] {
  const split = (output: string) =>
    new Map(
      output
        .split("\n")
        .map((line) => /^\s*(\d+)\s+(.*)$/.exec(line))
        .filter((m): m is RegExpExecArray => m !== null)
        .map((m) => [m[1], m[2].trim()] as const),
    );
  const args = split(argsOutput);
  return [...split(commOutput)].map(([pid, comm]) => ({ pid, comm, args: args.get(pid) ?? "" }));
}

export function isManaged(name: string): boolean {
  return name.startsWith("Dozecam_") || name.startsWith(TEMP_PREFIX);
}

export function listAvds(home: string): Avd[] {
  if (!fs.existsSync(home)) return [];
  return fs
    .readdirSync(home)
    .filter((f) => f.endsWith(".ini"))
    .sort()
    .map((f) => {
      const name = f.slice(0, -".ini".length);
      const ini = path.join(home, f);
      const declared = readIni(fs.readFileSync(ini, "utf8")).path;
      const dir = declared && fs.existsSync(declared) ? declared : path.join(home, `${name}.avd`);
      return { name, dir, ini };
    });
}

/** Disk space a file or directory takes, counting allocated blocks so sparse images count small. */
export function diskUse(p: string): number {
  let st: fs.Stats;
  try {
    st = fs.lstatSync(p);
  } catch {
    return 0;
  }
  let total = st.blocks * 512;
  if (st.isDirectory()) {
    for (const entry of fs.readdirSync(p)) total += diskUse(path.join(p, entry));
  }
  return total;
}

/** When the emulator last wrote to the AVD: it rewrites files at the top of its directory on every boot. */
function lastUsed(avd: Avd): number {
  const times = [fs.statSync(avd.ini).mtimeMs];
  if (fs.existsSync(avd.dir)) {
    for (const entry of fs.readdirSync(avd.dir)) {
      // This tool makes the lock files, not the emulator.
      if (LOCK_FILES.includes(entry)) continue;
      times.push(fs.lstatSync(path.join(avd.dir, entry)).mtimeMs);
    }
  }
  return Math.max(...times);
}

export type Context = {
  dryRun: boolean;
  home: string;
  log: (line: string) => void;
};

function ps(field: "comm" | "args"): string {
  const result = spawnSync("ps", ["-A", "-o", `pid=,${field}=`], { encoding: "utf8" });
  // Treating every AVD as stopped when ps fails would let a wipe pull the
  // disk out from under a running emulator.
  if (result.status !== 0) throw new Error(`ps failed: ${result.stderr || result.error}`);
  return result.stdout;
}

function processes(): Process[] {
  return parseProcesses(ps("comm"), ps("args"));
}

function isRunning(avd: Avd): boolean {
  return runningAvds(processes(), [avd.name]).has(avd.name);
}

function qemuImg(args: string[], cwd: string): string {
  const bundled = path.join(sdkRoot(), "emulator", "qemu-img");
  const bin = fs.existsSync(bundled) ? bundled : "qemu-img";
  const result = spawnSync(bin, args, { cwd, encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`qemu-img ${args.join(" ")} failed: ${result.stderr || result.error}`);
  }
  return result.stdout;
}

function remove(ctx: Context, p: string) {
  if (!ctx.dryRun) fs.rmSync(p, { recursive: true, force: true });
}

function updateConfig(ctx: Context, avd: Avd, values: (current: Record<string, string>) => Record<string, string>): boolean {
  const file = path.join(avd.dir, "config.ini");
  if (!fs.existsSync(file)) {
    ctx.log(`${avd.name}: no config.ini, left as is`);
    return false;
  }
  const text = fs.readFileSync(file, "utf8");
  const next = setIni(text, values(readIni(text)));
  if (next === text) return false;
  if (!ctx.dryRun) fs.writeFileSync(file, next);
  return true;
}

export function configure(ctx: Context, avd: Avd) {
  if (!fs.existsSync(path.join(avd.dir, "config.ini"))) {
    ctx.log(`${avd.name}: no config.ini, left as is`);
    return;
  }
  const changed = updateConfig(ctx, avd, () => COLD_BOOT);
  ctx.log(`${avd.name}: ${changed ? (ctx.dryRun ? "would boot cold" : "boots cold from its next boot") : "already boots cold"}`);
}

/**
 * Runs a command on each AVD in turn. One AVD that fails, such as one whose
 * directory or base image is gone, doesn't stop the rest.
 */
function each(ctx: Context, avds: Avd[], fn: (avd: Avd) => boolean | void) {
  let skipped = false;
  let failed = false;
  for (const avd of avds) {
    try {
      if (fn(avd) === false) skipped = true;
    } catch (err) {
      ctx.log(`${avd.name}: failed: ${err instanceof Error ? err.message : err}`);
      failed = true;
    }
  }
  return { skipped, failed, ok: !skipped && !failed };
}

/**
 * The format of an overlay's base. An overlay doesn't always record it, and
 * guessing wrong (a qcow2 base read as raw) corrupts the rewritten image, so
 * ask the base itself.
 */
function backingFormat(info: Record<string, any>, dir: string): string {
  if (info["backing-filename-format"]) return info["backing-filename-format"];
  const format = JSON.parse(qemuImg(["info", "--output=json", info["backing-filename"]], dir)).format;
  if (!format) throw new Error(`can't tell the format of ${info["backing-filename"]}`);
  return format;
}

/**
 * The AVD's lock files, taken in this order. AVDs are shared by every project
 * on the machine, and CloudMount runs its own copy of this tool, with its own
 * weekly schedule, on AVDs such as Pixel_8a: it locks the second file, so
 * holding both keeps the two projects' runs off the same AVD.
 */
export const LOCK_FILES = [".dozecam-avd.lock", ".cloudmount-avd.lock"];
const LOCK_EX = 2;
const LOCK_NB = 4;

let flockFn: ((fd: number, op: number) => number) | undefined;

/** flock(2), which Node's fs lacks. */
function flock(fd: number, op: number): number {
  if (!flockFn) {
    const libc = process.platform === "darwin" ? "/usr/lib/libSystem.B.dylib" : "libc.so.6";
    flockFn = dlopen(libc, { flock: { args: [FFIType.i32, FFIType.i32], returns: FFIType.i32 } }).symbols.flock;
  }
  return flockFn(fd, op);
}

/**
 * Runs fn holding the AVD's lock, so a scheduled run and one started by hand
 * never rewrite or delete the same AVD at once. Returns false, without
 * running fn, while another run holds it. The kernel's flock releases the
 * lock when its process ends, so a run that died never leaves it held; the
 * lock file stays, because deleting it would let a run that opened it
 * before the delete lock a file that no longer guards anything.
 */
export function withLock(ctx: Context, avd: Avd, fn: () => boolean): boolean {
  // Without its directory there's nothing to rewrite, so nothing to race over.
  if (ctx.dryRun || !fs.existsSync(avd.dir)) return fn();
  const fds: number[] = [];
  try {
    for (const file of LOCK_FILES) {
      const fd = fs.openSync(path.join(avd.dir, file), "a");
      fds.push(fd);
      if (flock(fd, LOCK_EX | LOCK_NB) !== 0) {
        ctx.log(`${avd.name}: another run is working on it, skipped`);
        return false;
      }
    }
    return fn();
  } finally {
    for (const fd of fds) fs.closeSync(fd);
  }
}

/** Returns false when the AVD was skipped. */
export function compact(ctx: Context, avd: Avd, force: boolean): boolean {
  return withLock(ctx, avd, () => compactLocked(ctx, avd, force));
}

function compactLocked(ctx: Context, avd: Avd, force: boolean): boolean {
  if (isRunning(avd)) {
    ctx.log(`${avd.name}: running, skipped`);
    return false;
  }
  const snapshotsDir = path.join(avd.dir, "snapshots");
  const saved = fs.existsSync(snapshotsDir) ? fs.readdirSync(snapshotsDir) : [];
  const named = saved.filter((s) => s !== QUICK_BOOT_SNAPSHOT && !s.startsWith("."));
  // Rewriting an image drops every snapshot inside it, the ones saved by
  // name in the emulator's Snapshots panel as well.
  if (named.length > 0 && !force) {
    ctx.log(`${avd.name}: has saved snapshots (${named.join(", ")}), skipped; --force drops them`);
    return false;
  }

  // First, so that a rewrite failing halfway leaves no Quick Boot snapshot
  // pointing at the disk state the rewritten images no longer hold.
  let freed = 0;
  if (saved.length > 0) {
    freed += diskUse(snapshotsDir);
    remove(ctx, snapshotsDir);
    ctx.log(`${avd.name}: ${ctx.dryRun ? "would drop" : "dropped"} its snapshots; it boots cold next time`);
  }

  // Holding the lock, any rewrite left over belongs to a run that died.
  if (!ctx.dryRun) {
    for (const f of fs.readdirSync(avd.dir).filter((f) => f.endsWith(".compact-tmp"))) {
      fs.rmSync(path.join(avd.dir, f), { force: true });
    }
  }

  for (const image of fs.readdirSync(avd.dir).filter((f) => f.endsWith(".qcow2")).sort()) {
    const file = path.join(avd.dir, image);
    const info = JSON.parse(qemuImg(["info", "--output=json", image], avd.dir));
    const hasSnapshots = Array.isArray(info.snapshots) && info.snapshots.length > 0;
    const size = diskUse(file);
    // What a rewrite keeps: the data the overlay itself holds, not its base's.
    const extents: { depth: number; data: boolean; length: number }[] = JSON.parse(
      qemuImg(["map", "--output=json", image], avd.dir),
    );
    const own = extents.filter((e) => e.depth === 0 && e.data).reduce((sum, e) => sum + e.length, 0);
    if (!force && !hasSnapshots && size - own < MIN_COMPACT_GAIN) continue;
    if (ctx.dryRun) {
      ctx.log(`${avd.name}: would rewrite ${image} (${formatBytes(size)}, about ${formatBytes(own)} after)`);
      continue;
    }
    const tmp = `${image}.${process.pid}.compact-tmp`;
    const args = ["convert", "-O", "qcow2"];
    // Keep the image an overlay on its base, as the emulator made it,
    // rather than copying the base into it.
    if (info["backing-filename"]) {
      args.push("-B", info["backing-filename"], "-o", `backing_fmt=${backingFormat(info, avd.dir)}`);
    }
    try {
      qemuImg([...args, image, tmp], avd.dir);
      if (isRunning(avd)) {
        ctx.log(`${avd.name}: started while ${image} was rewritten, left as is`);
        return false;
      }
      fs.renameSync(path.join(avd.dir, tmp), file);
    } finally {
      fs.rmSync(path.join(avd.dir, tmp), { force: true });
    }
    const after = diskUse(file);
    freed += size - after;
    ctx.log(`${avd.name}: rewrote ${image}, ${formatBytes(size)} -> ${formatBytes(after)}`);
  }

  if (!ctx.dryRun) ctx.log(`${avd.name}: ${freed > 0 ? `freed ${formatBytes(freed)}` : "nothing to reclaim"}`);
  return true;
}

/** Returns false when the AVD was skipped. */
export function wipe(ctx: Context, avd: Avd, dataSize: string): boolean {
  return withLock(ctx, avd, () => wipeLocked(ctx, avd, dataSize));
}

function wipeLocked(ctx: Context, avd: Avd, dataSize: string): boolean {
  if (isRunning(avd)) {
    ctx.log(`${avd.name}: running, skipped`);
    return false;
  }
  const before = diskUse(avd.dir);
  for (const f of WIPE_FILES) remove(ctx, path.join(avd.dir, f));
  updateConfig(ctx, avd, (current) => {
    const values = { ...COLD_BOOT };
    const size = parseSize(current["disk.dataPartition.size"] ?? "");
    // Only shrink: an AVD made bigger on purpose stays as big as it was.
    if (!(size <= parseSize(dataSize))) values["disk.dataPartition.size"] = dataSize;
    return values;
  });
  const freed = before - diskUse(avd.dir);
  ctx.log(
    ctx.dryRun
      ? `${avd.name}: would wipe its data (${formatBytes(before)} on disk now)`
      : `${avd.name}: wiped, freed ${formatBytes(freed)}; it boots fresh next time`,
  );
  return true;
}

export function prune(ctx: Context, avds: Avd[], days: number, now = Date.now()): Avd[] {
  const kept: Avd[] = [];
  for (const avd of avds) {
    if (!avd.name.startsWith(TEMP_PREFIX)) {
      kept.push(avd);
      continue;
    }
    const idleDays = (now - lastUsed(avd)) / 86_400_000;
    if (idleDays < days || isRunning(avd)) {
      kept.push(avd);
      continue;
    }
    // A config pointing elsewhere could make rm reach outside the AVD.
    if (path.basename(avd.dir) !== `${avd.name}.avd`) {
      ctx.log(`${avd.name}: its directory ${avd.dir} isn't ${avd.name}.avd, left as is`);
      kept.push(avd);
      continue;
    }
    const deleted = withLock(ctx, avd, () => {
      remove(ctx, avd.dir);
      remove(ctx, avd.ini);
      return true;
    });
    if (!deleted) {
      kept.push(avd);
      continue;
    }
    ctx.log(`${avd.name}: ${ctx.dryRun ? "would delete" : "deleted"}, unused for ${Math.floor(idleDays)} days`);
  }
  return kept;
}

/**
 * Mondays at 11:00, an hour after CloudMount's schedule: the lock would keep
 * the two off an AVD they share, but one held by the other is skipped for
 * the week.
 */
export function launchAgentPlist(opts: {
  bun: string;
  script: string;
  extra: string[];
  env: Record<string, string>;
  log: string;
}): string {
  const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const str = (s: string) => `<string>${esc(s)}</string>`;
  const args = [opts.bun, opts.script, "auto", ...opts.extra].map((a) => `    ${str(a)}`).join("\n");
  const env = Object.entries(opts.env)
    .map(([k, v]) => `    <key>${esc(k)}</key>${str(v)}`)
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>${str(LAUNCH_AGENT_LABEL)}
  <key>ProgramArguments</key>
  <array>
${args}
  </array>
  <key>EnvironmentVariables</key>
  <dict>
${env}
  </dict>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Weekday</key><integer>1</integer>
    <key>Hour</key><integer>11</integer>
    <key>Minute</key><integer>0</integer>
  </dict>
  <key>StandardOutPath</key>${str(opts.log)}
  <key>StandardErrorPath</key>${str(opts.log)}
</dict>
</plist>
`;
}

function schedule(ctx: Context, action: string | undefined, extra: string[]) {
  if (process.platform !== "darwin") throw new UsageError("schedule only supports macOS (launchd)");
  const home = os.homedir();
  const plist = path.join(home, "Library/LaunchAgents", `${LAUNCH_AGENT_LABEL}.plist`);
  // A copy, because the checkout the schedule was installed from may be a
  // worktree that's gone by the time it runs. Install again to update it.
  const script = path.join(home, "Library/Application Support/Dozecam/avd.ts");
  const domain = `gui/${process.getuid?.()}`;
  const launchctl = (...args: string[]) => spawnSync("launchctl", args, { encoding: "utf8" });

  if (action === "remove") {
    if (!ctx.dryRun) {
      launchctl("bootout", domain, plist);
      fs.rmSync(plist, { force: true });
      fs.rmSync(script, { force: true });
    }
    ctx.log(`${ctx.dryRun ? "would remove" : "removed"} the weekly run`);
    return;
  }
  if (action !== "install") throw new UsageError("schedule takes install or remove");

  const bun = Bun.which("bun") ?? process.execPath;
  const log = path.join(home, "Library/Logs/Dozecam/avd-reclaim.log");
  const content = launchAgentPlist({
    bun,
    script,
    extra,
    log,
    env: {
      ANDROID_HOME: sdkRoot(),
      ANDROID_AVD_HOME: ctx.home,
      PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
    },
  });
  if (ctx.dryRun) {
    ctx.log(`would write ${plist}:\n${content}`);
    return;
  }
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.mkdirSync(path.dirname(log), { recursive: true });
  fs.mkdirSync(path.dirname(plist), { recursive: true });
  fs.copyFileSync(import.meta.path, script);
  fs.writeFileSync(plist, content);
  launchctl("bootout", domain, plist);
  const loaded = launchctl("bootstrap", domain, plist);
  if (loaded.status !== 0) throw new Error(`launchctl bootstrap failed: ${loaded.stderr}`);
  ctx.log(`runs auto every Monday at 11:00${extra.length ? ` (also on ${extra.join(", ")})` : ""}; log: ${log}`);
}

function status(ctx: Context, avds: Avd[]) {
  const running = runningAvds(processes(), avds.map((a) => a.name));
  const rows = avds.map((avd) => {
    const config = path.join(avd.dir, "config.ini");
    const ini = fs.existsSync(config) ? readIni(fs.readFileSync(config, "utf8")) : {};
    const use = diskUse(avd.dir);
    return { avd, use, cells: [
      avd.name,
      running.has(avd.name) ? "running" : "stopped",
      ini["fastboot.forceColdBoot"] === "yes" ? "cold" : "quick",
      ini["disk.dataPartition.size"] ?? "?",
      formatBytes(use),
    ] };
  });
  const header = ["AVD", "STATE", "BOOT", "DATA", "ON DISK"];
  const widths = header.map((h, i) => Math.max(h.length, ...rows.map((r) => r.cells[i].length)));
  const line = (cells: string[]) => cells.map((c, i) => c.padEnd(widths[i])).join("  ").trimEnd();
  ctx.log(line(header));
  for (const r of rows) ctx.log(line(r.cells));
  ctx.log(`total ${formatBytes(rows.reduce((sum, r) => sum + r.use, 0))}`);
}

function pick(avds: Avd[], names: string[]): Avd[] {
  return names.map((name) => {
    const avd = avds.find((a) => a.name === name);
    if (!avd) throw new UsageError(`no AVD named ${name} in ${avds.length ? path.dirname(avds[0].ini) : "the AVD home"}`);
    return avd;
  });
}

function takeOption(args: string[], flag: string): string | undefined {
  const i = args.indexOf(flag);
  if (i < 0) return undefined;
  const value = args[i + 1];
  if (value === undefined || value.startsWith("--")) throw new UsageError(`${flag} needs a value`);
  args.splice(i, 2);
  return value;
}

function takeFlag(args: string[], flag: string): boolean {
  const i = args.indexOf(flag);
  if (i >= 0) args.splice(i, 1);
  return i >= 0;
}

/** Runs a command; returns the exit code. */
export function main(argv: string[], log: (line: string) => void = console.log): number {
  const args = [...argv];
  const ctx: Context = { dryRun: takeFlag(args, "--dry-run"), home: avdHome(), log };
  const command = args.shift();
  try {
    const avds = listAvds(ctx.home);
    switch (command) {
      case "status":
        status(ctx, args.length ? pick(avds, args) : avds);
        return 0;
      case "configure": {
        if (!args.length) throw new UsageError("configure needs at least one AVD");
        return each(ctx, pick(avds, args), (avd) => configure(ctx, avd)).ok ? 0 : 1;
      }
      case "compact": {
        const force = takeFlag(args, "--force");
        if (!args.length) throw new UsageError("compact needs at least one AVD");
        return each(ctx, pick(avds, args), (avd) => compact(ctx, avd, force)).ok ? 0 : 1;
      }
      case "wipe": {
        const dataSize = takeOption(args, "--data-size") ?? DEFAULT_DATA_SIZE;
        if (Number.isNaN(parseSize(dataSize))) throw new UsageError(`--data-size ${dataSize} isn't a size like 6G`);
        if (!args.length) throw new UsageError("wipe needs at least one AVD");
        return each(ctx, pick(avds, args), (avd) => wipe(ctx, avd, dataSize)).ok ? 0 : 1;
      }
      case "prune": {
        const days = Number(takeOption(args, "--days") ?? DEFAULT_PRUNE_DAYS);
        if (!(days >= 0)) throw new UsageError("--days needs a number of days");
        if (args.length) throw new UsageError(`prune takes no AVD names`);
        prune(ctx, avds, days);
        return 0;
      }
      case "auto": {
        // An extra AVD that's gone shouldn't stop the weekly run for the rest.
        const missing = args.filter((name) => !avds.some((a) => a.name === name));
        for (const name of missing) log(`${name}: no such AVD, skipped`);
        const kept = prune(ctx, avds, DEFAULT_PRUNE_DAYS);
        const managed = kept.filter((a) => isManaged(a.name) || args.includes(a.name));
        // A running AVD is expected here, so only failures fail the run.
        const { failed } = each(ctx, managed, (avd) => {
          configure(ctx, avd);
          return compact(ctx, avd, false);
        });
        return failed ? 1 : 0;
      }
      case "schedule":
        schedule(ctx, args.shift(), args);
        return 0;
      case undefined:
      case "help":
      case "--help":
      case "-h":
        log(USAGE);
        return command === undefined ? 2 : 0;
      default:
        throw new UsageError(`unknown command ${command}`);
    }
  } catch (err) {
    if (err instanceof UsageError) {
      console.error(`${err.message}\n\n${USAGE}`);
      return 2;
    }
    console.error(err instanceof Error ? err.message : err);
    return 1;
  }
}

if (import.meta.main) process.exit(main(process.argv.slice(2)));
