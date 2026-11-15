# Builds jniLibs/<abi>/ (executables) and assets/runtime-<abi>.zip (libs + python) from a Termux stage dir.
import os, sys, shutil, subprocess, zipfile, re
arch, abi = sys.argv[1], sys.argv[2]
root = os.path.abspath(os.path.dirname(__file__) + "/..")
usr = f"{root}/stage-{arch}/data/data/com.termux/files/usr"
app = os.path.abspath(root + "/../app/src/main")
jni = f"{app}/jniLibs/{abi}"
shutil.rmtree(jni, ignore_errors=True); os.makedirs(jni)
exes = {"python3.14": "libpython_exec.so", "ssh": "libssh_exec.so", "ssh-keygen": "libsshkeygen_exec.so"}
for src, dst in exes.items():
    shutil.copy(f"{usr}/bin/{src}", f"{jni}/{dst}"); os.chmod(f"{jni}/{dst}", 0o755)

def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"\[(lib[^\]]+)\]", out.split("RUNPATH")[0] + out) if out else []
system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so"}  # libz.so (system) vs libz.so.1 (termux)
py = "lib/python3.14"
files = []  # (abs, arcname)
skipdirs = {"test", "idlelib", "tkinter", "turtledemo", "ensurepip", "pydoc_data", "lib2to3", "__pycache__", "venv", "config-3.14-aarch64-linux-android", "_pyrepl", "site-packages"}
for d, dirs, fs in os.walk(f"{usr}/{py}"):
    rel = os.path.relpath(d, usr)
    dirs[:] = [x for x in dirs if x not in skipdirs and not x.startswith("config-")]
    for f in fs:
        if f.endswith((".pyc", ".a", ".exe")): continue
        if "_tkinter" in f or "_curses" in f or "readline" in f or "_gdbm" in f or "_dbm" in f: continue
        files.append((f"{d}/{f}", f"{rel}/{f}"))
sp = f"{usr}/{py}/site-packages"
for d, dirs, fs in os.walk(f"{sp}/borg"):
    for f in fs: files.append((f"{d}/{f}", os.path.relpath(f"{d}/{f}", usr)))
extra = {f"{root}/dl/py/msgpack-1.0.8/msgpack": f"{py}/site-packages/msgpack", f"{root}/dl/py/pkg/packaging": f"{py}/site-packages/packaging"}
for s, a in extra.items():
    for d, dirs, fs in os.walk(s):
        for f in fs:
            if f.endswith(".py"): files.append((f"{d}/{f}", f"{a}/{os.path.relpath(f'{d}/{f}', s)}"))
# shared lib closure
todo = [f"{usr}/bin/{s}" for s in exes] + [a for a, _ in files if a.endswith(".so")]
libs, seen = {}, set()
while todo:
    p = todo.pop()
    for n in needed(p):
        if n in seen or n in system: continue
        seen.add(n)
        cand = f"{usr}/lib/{n}"
        if os.path.exists(cand):
            libs[n] = os.path.realpath(cand); todo.append(cand)
        else: print("system/missing:", n)
for n, real in libs.items(): files.append((real, f"lib/{n}"))
files.append((f"{usr}/etc/tls/cert.pem", "etc/tls/cert.pem"))
# Lizenztexte der enthaltenen Pakete (runtime/licenses, siehe THIRD_PARTY_NOTICES.md)
for d, dirs, fs in os.walk(f"{root}/licenses"):
    for f in fs: files.append((f"{d}/{f}", "licenses/" + os.path.relpath(f"{d}/{f}", f"{root}/licenses")))
os.makedirs(f"{app}/assets", exist_ok=True)
z = f"{app}/assets/runtime-{abi}.zip"
with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
    for a, n in sorted(set(files), key=lambda x: x[1]): zf.write(a, n)
print(abi, len(files), "files", os.path.getsize(z) // 1024, "KiB; libs:", " ".join(sorted(libs)))
