import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  LOCK_FILES,
  launchAgentPlist,
  parseProcesses,
  parseSize,
  readIni,
  runningAvds,
  setIni,
} from "./avd";

const SCRIPT = path.join(import.meta.dir, "avd.ts");

describe("setIni", () => {
  test("replaces values and keeps the other lines", () => {
    const text = "AvdId=X\nfastboot.forceColdBoot=no\nhw.ramSize=2048\n";
    expect(setIni(text, { "fastboot.forceColdBoot": "yes" })).toBe(
      "AvdId=X\nfastboot.forceColdBoot=yes\nhw.ramSize=2048\n",
    );
  });

  test("keeps the emulator's spaced style, also for appended keys", () => {
    const text = "AvdId = X\nfastboot.forceColdBoot = no\n";
    expect(setIni(text, { "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no" })).toBe(
      "AvdId = X\nfastboot.forceColdBoot = yes\nfastboot.forceFastBoot = no\n",
    );
  });

  test("leaves the text unchanged when the values already match", () => {
    const text = "a = 1\nb=2\n";
    expect(setIni(text, { a: "1", b: "2" })).toBe(text);
  });

  test("replaces every duplicate of a key and reads CRLF files", () => {
    expect(setIni("a=1\r\nb=2\r\na=3\r\n", { a: "9" })).toBe("a=9\nb=2\na=9\n");
    expect(readIni("a = 1\r\nb=2")).toEqual({ a: "1", b: "2" });
  });
});

test("parseSize reads config.ini sizes", () => {
  expect(parseSize("6G")).toBe(6 * 1024 ** 3);
  expect(parseSize("6144M")).toBe(6 * 1024 ** 3);
  expect(parseSize("6442450944")).toBe(6442450944);
  expect(parseSize("1.5g")).toBe(1.5 * 1024 ** 3);
  expect(parseSize("12GB")).toBe(12 * 1024 ** 3);
  expect(parseSize("big")).toBeNaN();
});

describe("runningAvds", () => {
  const proc = (comm: string, args: string) => ({ pid: "1", comm, args });

  test("finds an AVD by the emulator's -avd or @ argument", () => {
    const running = runningAvds(
      [
        proc("/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64-headless", "/sdk/.../qemu-system-aarch64-headless -avd Pixel_8a -no-window"),
        proc("/sdk/emulator/emulator", "/sdk/emulator/emulator @Dozecam_Play_Phone"),
        proc("qemu-system-x86", "qemu-system-x86_64 -avd dz-tmp-api31"),
      ],
      ["Pixel_8a", "Dozecam_Play_Phone", "dz-tmp-api31", "grove_api30"],
    );
    expect([...running].sort()).toEqual(["Dozecam_Play_Phone", "Pixel_8a", "dz-tmp-api31"]);
  });

  test("ignores other processes whose command line names the AVD", () => {
    const running = runningAvds(
      [
        proc("/bin/zsh", "zsh -c nohup /sdk/emulator/emulator -avd Pixel_8a -no-window"),
        proc("/sdk/emulator/crashpad_handler", "crashpad_handler -avd Pixel_8a"),
      ],
      ["Pixel_8a"],
    );
    expect(running.size).toBe(0);
  });

  test("doesn't take one AVD's name for a longer one's", () => {
    const running = runningAvds([proc("qemu-system-aarch64", "qemu -avd Pixel_8a_2")], ["Pixel_8a"]);
    expect(running.size).toBe(0);
  });

  test("joins the comm and args listings by pid", () => {
    expect(parseProcesses("  12 /bin/zsh\n 345 /sdk/qemu-system-aarch64\n", "12 zsh -l\n345 /sdk/qemu-system-aarch64 -avd A\n")).toEqual([
      { pid: "12", comm: "/bin/zsh", args: "zsh -l" },
      { pid: "345", comm: "/sdk/qemu-system-aarch64", args: "/sdk/qemu-system-aarch64 -avd A" },
    ]);
  });
});

test("launchAgentPlist is a valid plist that runs auto with the extra AVDs", () => {
  const plist = launchAgentPlist({
    bun: "/opt/homebrew/bin/bun",
    script: "/Users/me/Library/Application Support/Dozecam/avd.ts",
    extra: ["Pixel_8a", "A&B"],
    env: { ANDROID_HOME: "/sdk" },
    log: "/tmp/log",
  });
  expect(plist).toContain("<string>auto</string>\n    <string>Pixel_8a</string>\n    <string>A&amp;B</string>");
  expect(plist).toContain("<key>ANDROID_HOME</key><string>/sdk</string>");
  expect(plist).toContain("<key>Label</key><string>app.dozecam.avd-reclaim</string>");
  // An hour after CloudMount's run, which shares AVDs such as Pixel_8a.
  expect(plist).toContain("<key>Hour</key><integer>11</integer>");
  if (process.platform === "darwin") {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "avd-plist-")), "a.plist");
    fs.writeFileSync(file, plist);
    expect(spawnSync("plutil", ["-lint", file]).status).toBe(0);
  }
});

describe("commands", () => {
  let root: string;
  let home: string;
  let fakes: string;

  // qemu-img and ps stand-ins: qemu-img logs its calls and reports the
  // internal snapshots an image has when an <image>.snap marker sits next
  // to it, leaves out its base's format with <image>.nofmt, fails info on
  // <image>.broken and fails to convert one with <image>.fail; ps lists what the test writes to fakes/comm and fakes/args.
  beforeEach(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), "avd-test-"));
    home = path.join(root, "avd");
    fakes = path.join(root, "fakes");
    fs.mkdirSync(home);
    fs.mkdirSync(path.join(root, "sdk", "emulator"), { recursive: true });
    fs.mkdirSync(fakes);
    const qemu = path.join(root, "sdk", "emulator", "qemu-img");
    fs.writeFileSync(
      qemu,
      `#!/bin/bash
echo "$(basename "$PWD")|$*" >> "${fakes}/qemu.log"
last="\${@: -1}"
case "$1" in
  info)
    # A base image reports its own format.
    [[ "$last" != *.qcow2 ]] && { echo '{"format":"qcow2"}'; exit 0; }
    fmt=',"backing-filename-format":"raw"'
    [ -f "$last.nofmt" ] && fmt=""
    snaps=""
    [ -f "$last.snap" ] && snaps=',"snapshots":[{"name":"default_boot"}]'
    [ -f "$last.broken" ] && { echo "broken image" >&2; exit 1; }
    echo "{\\"backing-filename\\":\\"\${last%.qcow2}\\"$fmt$snaps}" ;;
  # 1 KB of the overlay's own data over 4 KB from its base.
  map) echo '[{"start":0,"length":1024,"depth":0,"data":true},{"start":1024,"length":4096,"depth":1,"data":true}]' ;;
  convert)
    [ -f "\${@: -2:1}.fail" ] && { echo "write error" >&2; exit 1; }
    echo compacted > "$last" ;;
esac
`,
    );
    fs.chmodSync(qemu, 0o755);
    const ps = path.join(fakes, "ps");
    fs.writeFileSync(ps, `#!/bin/bash\ncase "$*" in *comm=*) cat "${fakes}/comm" ;; *) cat "${fakes}/args" ;; esac\n`);
    fs.chmodSync(ps, 0o755);
    setRunning([]);
  });

  afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

  function setRunning(names: string[]) {
    fs.writeFileSync(path.join(fakes, "comm"), names.map((n, i) => `${100 + i} /sdk/qemu-system-aarch64\n`).join(""));
    fs.writeFileSync(path.join(fakes, "args"), names.map((n, i) => `${100 + i} /sdk/qemu-system-aarch64 -avd ${n} -no-window\n`).join(""));
  }

  function makeAvd(name: string, config: string, files: string[] = [], dirName = `${name}.avd`) {
    const dir = path.join(home, dirName);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(home, `${name}.ini`), `avd.ini.encoding=UTF-8\npath=${dir}\npath.rel=avd/${dirName}\n`);
    fs.writeFileSync(path.join(dir, "config.ini"), config);
    for (const f of files) {
      fs.mkdirSync(path.dirname(path.join(dir, f)), { recursive: true });
      fs.writeFileSync(path.join(dir, f), "data");
    }
    return dir;
  }

  function run(...args: string[]) {
    const result = spawnSync(process.execPath, [SCRIPT, ...args], {
      encoding: "utf8",
      env: {
        ...process.env,
        ANDROID_AVD_HOME: home,
        ANDROID_HOME: path.join(root, "sdk"),
        PATH: `${fakes}:${process.env.PATH}`,
      },
    });
    return { code: result.status, out: result.stdout + result.stderr };
  }

  const qemuCalls = () => {
    const log = path.join(fakes, "qemu.log");
    return fs.existsSync(log) ? fs.readFileSync(log, "utf8").trim().split("\n") : [];
  };

  const DATA_FILES = [
    "userdata-qemu.img",
    "userdata-qemu.img.qcow2",
    "cache.img",
    "cache.img.qcow2",
    "encryptionkey.img",
    "encryptionkey.img.qcow2",
    "snapshots/default_boot/ram.bin",
  ];

  describe("wipe", () => {
    test("deletes the data, boots cold and caps the data partition", () => {
      const dir = makeAvd("Dozecam_Big", "AvdId=Dozecam_Big\ndisk.dataPartition.size=12G\nfastboot.forceFastBoot=yes\n", [
        ...DATA_FILES,
        "sdcard.img",
        "userdata.img",
      ]);
      const { code, out } = run("wipe", "Dozecam_Big");
      expect(code).toBe(0);
      expect(out).toContain("Dozecam_Big: wiped");
      for (const f of DATA_FILES) expect(fs.existsSync(path.join(dir, f))).toBe(false);
      expect(fs.existsSync(path.join(dir, "snapshots"))).toBe(false);
      // The SD card and the AVD's initial userdata.img aren't the emulator's to recreate.
      expect(fs.existsSync(path.join(dir, "sdcard.img"))).toBe(true);
      expect(fs.existsSync(path.join(dir, "userdata.img"))).toBe(true);
      expect(readIni(fs.readFileSync(path.join(dir, "config.ini"), "utf8"))).toEqual({
        AvdId: "Dozecam_Big",
        "disk.dataPartition.size": "6G",
        "fastboot.forceFastBoot": "no",
        "fastboot.forceColdBoot": "yes",
        "fastboot.forceChosenSnapshotBoot": "no",
      });
    });

    test("keeps a data partition already at or below the cap", () => {
      const dir = makeAvd("Small", "disk.dataPartition.size = 4294967296\n", DATA_FILES);
      expect(run("wipe", "Small").code).toBe(0);
      expect(readIni(fs.readFileSync(path.join(dir, "config.ini"), "utf8"))["disk.dataPartition.size"]).toBe("4294967296");
    });

    test("takes another cap with --data-size", () => {
      const dir = makeAvd("A", "disk.dataPartition.size=12G\n", DATA_FILES);
      expect(run("wipe", "--data-size", "8G", "A").code).toBe(0);
      expect(readIni(fs.readFileSync(path.join(dir, "config.ini"), "utf8"))["disk.dataPartition.size"]).toBe("8G");
      expect(run("wipe", "--data-size", "lots", "A").code).toBe(2);
    });

    test("skips a running AVD and fails, leaving it untouched", () => {
      const dir = makeAvd("Busy", "disk.dataPartition.size=12G\n", DATA_FILES);
      makeAvd("Idle", "disk.dataPartition.size=12G\n", DATA_FILES);
      setRunning(["Busy"]);
      const { code, out } = run("wipe", "Busy", "Idle");
      expect(code).toBe(1);
      expect(out).toContain("Busy: running, skipped");
      expect(out).toContain("Idle: wiped");
      for (const f of DATA_FILES) expect(fs.existsSync(path.join(dir, f))).toBe(true);
      expect(fs.readFileSync(path.join(dir, "config.ini"), "utf8")).toBe("disk.dataPartition.size=12G\n");
    });

    test("changes nothing with --dry-run", () => {
      const dir = makeAvd("A", "disk.dataPartition.size=12G\n", DATA_FILES);
      const { code, out } = run("--dry-run", "wipe", "A");
      expect(code).toBe(0);
      expect(out).toContain("A: would wipe");
      for (const f of DATA_FILES) expect(fs.existsSync(path.join(dir, f))).toBe(true);
      expect(fs.readFileSync(path.join(dir, "config.ini"), "utf8")).toBe("disk.dataPartition.size=12G\n");
    });

    test("refuses an AVD that doesn't exist", () => {
      const { code, out } = run("wipe", "Nope");
      expect(code).toBe(2);
      expect(out).toContain("no AVD named Nope");
    });
  });

  describe("compact", () => {
    test("rewrites images that hold a snapshot onto their base, and drops the snapshots", () => {
      const dir = makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "cache.img.qcow2", "snapshots/default_boot/ram.bin"]);
      const { code, out } = run("compact", "A");
      expect(code).toBe(0);
      expect(out).toContain("A: rewrote userdata-qemu.img.qcow2");
      expect(out).not.toContain("rewrote cache.img.qcow2");
      expect(qemuCalls()).toContainEqual(
        expect.stringMatching(
          /^A\.avd\|convert -O qcow2 -B userdata-qemu\.img -o backing_fmt=raw userdata-qemu\.img\.qcow2 userdata-qemu\.img\.qcow2\.\d+\.compact-tmp$/,
        ),
      );
      expect(fs.readFileSync(path.join(dir, "userdata-qemu.img.qcow2"), "utf8")).toBe("compacted\n");
      expect(fs.readdirSync(dir).filter((f) => f.endsWith(".compact-tmp"))).toEqual([]);
      expect(fs.existsSync(path.join(dir, "snapshots"))).toBe(false);
    });

    test("a failed rewrite leaves the image and no Quick Boot snapshot to resume from", () => {
      const dir = makeAvd("A", "x=1\n", [
        "cache.img.qcow2",
        "cache.img.qcow2.snap",
        "userdata-qemu.img.qcow2",
        "userdata-qemu.img.qcow2.snap",
        "userdata-qemu.img.qcow2.fail",
        "snapshots/default_boot/ram.bin",
      ]);
      const { code, out } = run("compact", "A");
      expect(code).toBe(1);
      expect(out).toContain("write error");
      expect(fs.readFileSync(path.join(dir, "cache.img.qcow2"), "utf8")).toBe("compacted\n");
      expect(fs.readFileSync(path.join(dir, "userdata-qemu.img.qcow2"), "utf8")).toBe("data");
      expect(fs.readdirSync(dir).filter((f) => f.endsWith(".compact-tmp"))).toEqual([]);
      expect(fs.existsSync(path.join(dir, "snapshots"))).toBe(false);
    });

    test("asks the base for its format when the overlay doesn't record it", () => {
      makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "userdata-qemu.img.qcow2.nofmt"]);
      expect(run("compact", "A").code).toBe(0);
      expect(qemuCalls()).toContain("A.avd|info --output=json userdata-qemu.img");
      expect(qemuCalls()).toContainEqual(expect.stringContaining("convert -O qcow2 -B userdata-qemu.img -o backing_fmt=qcow2 "));
    });

    test("estimates the size after from the overlay's own data", () => {
      makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap"]);
      expect(run("--dry-run", "compact", "A").out).toMatch(/would rewrite userdata-qemu\.img\.qcow2 \(.*, about 1\.0 KB after\)/);
    });

    test("rewrites every image with --force", () => {
      const dir = makeAvd("A", "x=1\n", ["cache.img.qcow2"]);
      expect(run("compact", "--force", "A").code).toBe(0);
      expect(fs.readFileSync(path.join(dir, "cache.img.qcow2"), "utf8")).toBe("compacted\n");
    });

    test("keeps snapshots saved by name unless forced", () => {
      const dir = makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin", "snapshots/before-upgrade/ram.bin"]);
      const skipped = run("compact", "A");
      expect(skipped.code).toBe(1);
      expect(skipped.out).toContain("has saved snapshots (before-upgrade)");
      expect(qemuCalls()).toEqual([]);
      expect(fs.existsSync(path.join(dir, "snapshots/before-upgrade"))).toBe(true);

      expect(run("compact", "--force", "A").code).toBe(0);
      expect(fs.existsSync(path.join(dir, "snapshots"))).toBe(false);
    });

    test("skips a running AVD", () => {
      const dir = makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin"]);
      setRunning(["A"]);
      expect(run("compact", "A").code).toBe(1);
      expect(qemuCalls()).toEqual([]);
      expect(fs.existsSync(path.join(dir, "snapshots/default_boot/ram.bin"))).toBe(true);
    });

    test("changes nothing with --dry-run", () => {
      const dir = makeAvd("A", "x=1\n", ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin"]);
      const { out } = run("--dry-run", "compact", "A");
      expect(out).toContain("A: would rewrite userdata-qemu.img.qcow2");
      expect(qemuCalls().some((c) => c.includes("convert"))).toBe(false);
      expect(fs.existsSync(path.join(dir, "snapshots/default_boot/ram.bin"))).toBe(true);
    });
  });

  describe("prune", () => {
    const age = (dir: string, ini: string, days: number) => {
      const t = new Date(Date.now() - days * 86_400_000);
      for (const f of [...fs.readdirSync(dir).map((e) => path.join(dir, e)), ini]) fs.utimesSync(f, t, t);
    };

    test("deletes temporary AVDs unused for longer than the limit", () => {
      const oldDir = makeAvd("dz-tmp-old", "x=1\n", ["userdata-qemu.img.qcow2"]);
      age(oldDir, path.join(home, "dz-tmp-old.ini"), 5);
      const recentDir = makeAvd("dz-tmp-recent", "x=1\n", ["userdata-qemu.img.qcow2"]);
      age(recentDir, path.join(home, "dz-tmp-recent.ini"), 1);
      const busyDir = makeAvd("dz-tmp-busy", "x=1\n", ["userdata-qemu.img.qcow2"]);
      age(busyDir, path.join(home, "dz-tmp-busy.ini"), 5);
      const keptDir = makeAvd("Dozecam_Kept", "x=1\n", ["userdata-qemu.img.qcow2"]);
      age(keptDir, path.join(home, "Dozecam_Kept.ini"), 30);
      setRunning(["dz-tmp-busy"]);

      const { code, out } = run("prune");
      expect(code).toBe(0);
      expect(out).toContain("dz-tmp-old: deleted, unused for 5 days");
      expect(fs.existsSync(oldDir)).toBe(false);
      expect(fs.existsSync(path.join(home, "dz-tmp-old.ini"))).toBe(false);
      for (const dir of [recentDir, busyDir, keptDir]) expect(fs.existsSync(dir)).toBe(true);

      expect(run("prune", "--days", "0").code).toBe(0);
      expect(fs.existsSync(recentDir)).toBe(false);
    });

    test("doesn't count a fresh lock file as use", () => {
      const dir = makeAvd("dz-tmp-locked", "x=1\n", ["userdata-qemu.img.qcow2"]);
      age(dir, path.join(home, "dz-tmp-locked.ini"), 5);
      for (const f of LOCK_FILES) fs.writeFileSync(path.join(dir, f), "");
      expect(run("prune").out).toContain("dz-tmp-locked: deleted, unused for 5 days");
      expect(fs.existsSync(dir)).toBe(false);
    });

    test("leaves an AVD whose directory isn't named after it", () => {
      const dir = makeAvd("dz-tmp-odd", "x=1\n", [], "elsewhere");
      age(dir, path.join(home, "dz-tmp-odd.ini"), 10);
      const { out } = run("prune");
      expect(out).toContain("isn't dz-tmp-odd.avd, left as is");
      expect(fs.existsSync(dir)).toBe(true);
    });
  });

  describe("configure and auto", () => {
    test("configure sets cold boot once", () => {
      const dir = makeAvd("A", "AvdId = A\nfastboot.forceFastBoot = yes\n");
      expect(run("configure", "A").out).toContain("A: boots cold from its next boot");
      expect(fs.readFileSync(path.join(dir, "config.ini"), "utf8")).toBe(
        "AvdId = A\nfastboot.forceFastBoot = no\nfastboot.forceColdBoot = yes\nfastboot.forceChosenSnapshotBoot = no\n",
      );
      expect(run("configure", "A").out).toContain("A: already boots cold");
    });

    test("auto looks after Dozecam_*, dz-tmp-* and the named AVDs only", () => {
      const files = ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin"];
      const ours = makeAvd("Dozecam_Phone", "fastboot.forceFastBoot=yes\n", files);
      const temp = makeAvd("dz-tmp-api31", "fastboot.forceFastBoot=yes\n", files);
      const extra = makeAvd("Pixel_8a", "fastboot.forceFastBoot=yes\n", files);
      const other = makeAvd("grove_api30", "fastboot.forceFastBoot=yes\n", files);
      const busy = makeAvd("Dozecam_Busy", "fastboot.forceFastBoot=yes\n", files);
      setRunning(["Dozecam_Busy"]);

      const { code, out } = run("auto", "Pixel_8a", "Gone");
      expect(code).toBe(0);
      expect(out).toContain("Gone: no such AVD, skipped");
      expect(out).toContain("Dozecam_Busy: running, skipped");
      for (const dir of [ours, temp, extra]) {
        expect(readIni(fs.readFileSync(path.join(dir, "config.ini"), "utf8"))["fastboot.forceColdBoot"]).toBe("yes");
        expect(fs.existsSync(path.join(dir, "snapshots"))).toBe(false);
      }
      // A running AVD is still set to boot cold; its disk waits for a later run.
      expect(readIni(fs.readFileSync(path.join(busy, "config.ini"), "utf8"))["fastboot.forceColdBoot"]).toBe("yes");
      expect(fs.existsSync(path.join(busy, "snapshots"))).toBe(true);
      expect(fs.readFileSync(path.join(other, "config.ini"), "utf8")).toBe("fastboot.forceFastBoot=yes\n");
      expect(fs.existsSync(path.join(other, "snapshots"))).toBe(true);
      expect(qemuCalls().some((c) => c.startsWith("grove_api30.avd|"))).toBe(false);
    });
  });

  test("one AVD that fails doesn't stop auto from looking after the rest", () => {
    const files = ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin"];
    makeAvd("Dozecam_A", "x=1\n", [...files, "userdata-qemu.img.qcow2.broken"]);
    fs.writeFileSync(path.join(home, "Dozecam_Gone.ini"), `path=${path.join(home, "Dozecam_Gone.avd")}\n`);
    const healthy = makeAvd("Dozecam_B", "fastboot.forceFastBoot=yes\n", files);
    const { code, out } = run("auto");
    expect(code).toBe(1);
    expect(out).toContain("Dozecam_A: failed: qemu-img info");
    expect(out).toContain("Dozecam_Gone: no config.ini, left as is");
    expect(out).toContain("Dozecam_Gone: failed:");
    expect(readIni(fs.readFileSync(path.join(healthy, "config.ini"), "utf8"))["fastboot.forceColdBoot"]).toBe("yes");
    expect(fs.existsSync(path.join(healthy, "snapshots"))).toBe(false);
  });

  describe("lock", () => {
    const files = ["userdata-qemu.img.qcow2", "userdata-qemu.img.qcow2.snap", "snapshots/default_boot/ram.bin"];
    const makeOld = (name: string, dir?: string) => {
      const old = new Date(Date.now() - 10 * 86_400_000);
      fs.utimesSync(path.join(home, `${name}.ini`), old, old);
      if (dir) for (const f of fs.readdirSync(dir)) fs.utimesSync(path.join(dir, f), old, old);
    };

    // Another run, standing in for one in progress: a process that holds the
    // AVD's lock until the test closes its stdin.
    async function holdLock(dir: string, file: string) {
      const holder = Bun.spawn(
        [
          process.execPath,
          "-e",
          `import { dlopen, FFIType } from "bun:ffi";
           import fs from "node:fs";
           const libc = process.platform === "darwin" ? "/usr/lib/libSystem.B.dylib" : "libc.so.6";
           const { flock } = dlopen(libc, { flock: { args: [FFIType.i32, FFIType.i32], returns: FFIType.i32 } }).symbols;
           const fd = fs.openSync(${JSON.stringify(path.join(dir, file))}, "a");
           console.log(flock(fd, 6) === 0 ? "held" : "busy");
           for await (const _ of process.stdin) {}`,
        ],
        { stdin: "pipe", stdout: "pipe" },
      );
      const { value } = await holder.stdout.getReader().read();
      expect(new TextDecoder().decode(value).trim()).toBe("held");
      return () => {
        holder.stdin.end();
        return holder.exited;
      };
    }

    test.each(LOCK_FILES)("a run that holds %s keeps compact, wipe and prune away from the AVD", async (lockFile) => {
      const dir = makeAvd("dz-tmp-a", "x=1\n", files);
      const release = await holdLock(dir, lockFile);
      makeOld("dz-tmp-a", dir);
      try {
        for (const args of [["compact", "dz-tmp-a"], ["wipe", "dz-tmp-a"]]) {
          const { code, out } = run(...args);
          expect(code).toBe(1);
          expect(out).toContain("dz-tmp-a: another run is working on it, skipped");
        }
        expect(run("prune").out).toContain("dz-tmp-a: another run is working on it, skipped");
        expect(qemuCalls()).toEqual([]);
        for (const f of files) expect(fs.existsSync(path.join(dir, f))).toBe(true);
      } finally {
        await release();
      }
      // Once that run ends, the AVD is free again.
      expect(run("compact", "dz-tmp-a").code).toBe(0);
    });

    test("a lock file left by a run that died doesn't hold the AVD, and its leftover rewrite is cleared", () => {
      const dir = makeAvd("A", "x=1\n", [...files, "userdata-qemu.img.qcow2.4242.compact-tmp", ...LOCK_FILES]);
      const { code, out } = run("compact", "A");
      expect(code).toBe(0);
      expect(out).toContain("A: rewrote userdata-qemu.img.qcow2");
      expect(fs.existsSync(path.join(dir, "userdata-qemu.img.qcow2.4242.compact-tmp"))).toBe(false);
    });

    test("prune removes the entry of a temporary AVD whose directory is gone", () => {
      fs.writeFileSync(path.join(home, "dz-tmp-gone.ini"), `path=${path.join(home, "dz-tmp-gone.avd")}\n`);
      makeOld("dz-tmp-gone");
      expect(run("prune").code).toBe(0);
      expect(fs.existsSync(path.join(home, "dz-tmp-gone.ini"))).toBe(false);
    });
  });

  test("status lists every AVD with its state and boot mode", () => {
    makeAvd("A", "fastboot.forceColdBoot=yes\ndisk.dataPartition.size=6G\n", ["userdata-qemu.img.qcow2"]);
    makeAvd("B", "disk.dataPartition.size=12G\n");
    setRunning(["B"]);
    const { code, out } = run("status");
    expect(code).toBe(0);
    expect(out).toMatch(/^A\s+stopped\s+cold\s+6G\s+/m);
    expect(out).toMatch(/^B\s+running\s+quick\s+12G\s+/m);
  });

  test("a failing ps stops the run instead of treating AVDs as stopped", () => {
    const dir = makeAvd("A", "x=1\n", DATA_FILES);
    fs.writeFileSync(path.join(fakes, "ps"), "#!/bin/bash\nexit 1\n");
    const { code, out } = run("wipe", "A");
    expect(code).toBe(1);
    expect(out).toContain("ps failed");
    for (const f of DATA_FILES) expect(fs.existsSync(path.join(dir, f))).toBe(true);
  });
});
