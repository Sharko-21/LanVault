import { existsSync, readdirSync } from "node:fs";
import { join, delimiter } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = fileURLToPath(new URL("../", import.meta.url));
const target = process.argv.find((arg) => arg.startsWith("--target="))?.split("=")[1] ?? "all";
if (!["all", "web", "android", "desktop", "server"].includes(target))
  throw new Error("Unknown target");
const portable = process.argv.includes("--portable");
const strict = process.argv.includes("--strict");
const checks = [];

function locate(command) {
  const suffixes = process.platform === "win32" ? [".exe", ".cmd", ".bat", ""] : [""];
  for (const entry of (process.env.PATH ?? "").split(delimiter)) {
    if (!entry) continue;
    for (const suffix of suffixes) {
      const candidate = join(entry, command + suffix);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

function executableVersion(path, args) {
  if (!path) return null;
  const result = spawnSync(path, args, { cwd: root, encoding: "utf8", timeout: 15000 });
  if (result.status !== 0) return null;
  return `${result.stdout ?? ""} ${result.stderr ?? ""}`.trim();
}

function add(name, available, detail) {
  checks.push({ name, status: available ? "available" : "missing", detail });
}

if (["all", "web", "desktop"].includes(target)) {
  add("Node.js 24", Number(process.versions.node.split(".")[0]) === 24, process.version);
  add("pnpm", Boolean(locate("pnpm")), "Pinned version: 11.25.0");
}

if (["all", "android"].includes(target)) {
  const java = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
    : locate("java");
  const javaVersion = executableVersion(java, ["-version"]);
  add(
    "JDK 17",
    Boolean(javaVersion && /version "17[.\"]/.test(javaVersion)),
    javaVersion ?? "Install JDK 17 and set JAVA_HOME",
  );
  const sdk =
    process.env.ANDROID_HOME ??
    process.env.ANDROID_SDK_ROOT ??
    join(process.env.LOCALAPPDATA ?? "", "Android", "Sdk");
  add(
    "Android platform 37",
    existsSync(join(sdk, "platforms", "android-37", "android.jar")) ||
      existsSync(join(sdk, "platforms", "android-37.0", "android.jar")),
    "Set ANDROID_HOME to an SDK containing platforms;android-37.0",
  );
  add(
    "Android Build Tools 36.0.0",
    existsSync(join(sdk, "build-tools", "36.0.0")),
    "Install build-tools;36.0.0",
  );
  add(
    "Gradle Wrapper",
    existsSync(join(root, "android", "gradle", "wrapper", "gradle-wrapper.jar")),
    "Gradle 9.3.1; SHA-256 is checked separately",
  );
}

if (["all", "desktop", "server"].includes(target)) {
  const rustVersion = executableVersion(process.env.RUSTC ?? locate("rustc"), ["--version"]);
  add(
    "Rust 1.94.0",
    Boolean(rustVersion?.startsWith("rustc 1.94.0 ")),
    rustVersion ?? "Install the toolchain from rust-toolchain.toml",
  );
  add("Cargo", Boolean(locate("cargo")), "Cargo on PATH");
  if (process.platform === "win32") {
    if (portable) {
      add(
        "Portable GNU linker",
        existsSync(process.env.CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER ?? ""),
        "Dot-source tooling/use-portable-rust.ps1",
      );
    } else {
      const programFilesX86 = process.env["ProgramFiles(x86)"] ?? "C:\\Program Files (x86)";
      const vswhere = join(programFilesX86, "Microsoft Visual Studio", "Installer", "vswhere.exe");
      const installation = executableVersion(vswhere, [
        "-latest",
        "-products",
        "*",
        "-requires",
        "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
        "-property",
        "installationPath",
      ]);
      add(
        "MSVC C++ tools",
        Boolean(installation),
        "Visual Studio Build Tools: Desktop development with C++",
      );
      add(
        "Windows SDK",
        existsSync(join(programFilesX86, "Windows Kits", "10", "Lib")),
        "Install a Windows 10/11 SDK with the C++ workload",
      );
    }
    const programFilesX86 = process.env["ProgramFiles(x86)"] ?? "C:\\Program Files (x86)";
    const candidates = [
      join(programFilesX86, "Microsoft", "EdgeWebView", "Application"),
      join(
        process.env.ProgramFiles ?? "C:\\Program Files",
        "Microsoft",
        "EdgeWebView",
        "Application",
      ),
      join(process.env.LOCALAPPDATA ?? "", "Microsoft", "EdgeWebView", "Application"),
    ];
    const found = candidates.some(
      (candidate) =>
        existsSync(candidate) && readdirSync(candidate).some((name) => /^\d+\./.test(name)),
    );
    if (target !== "server")
      add("WebView2 runtime", found, "Evergreen WebView2 required for the Windows UI");
  } else add("Windows host", false, "Native Windows checks must run on Windows");
}

const report = {
  target,
  strict,
  checks,
  ready: checks.every((check) => check.status === "available"),
};
console.log(JSON.stringify(report, null, 2));
if (strict && !report.ready) process.exitCode = 1;
