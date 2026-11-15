import sys, os, re, urllib.request, subprocess
arch, out = sys.argv[1], sys.argv[2]
roots = ["borgbackup", "openssh"]
skip = {"python-pip", "openssh-sftp-server", "termux-auth", "ncurses-ui-libs", "readline", "gdbm", "libedit-dummy", "resolv-conf", "dash", "termux-exec", "bash"}
pk = {}
cur = {}
for line in open(f"dl/Packages-{arch}", encoding="utf-8"):
    line = line.rstrip("\n")
    if not line:
        if "Package" in cur: pk[cur["Package"]] = cur
        cur = {}; continue
    if ":" in line and not line.startswith(" "):
        k, v = line.split(":", 1); cur[k] = v.strip()
if "Package" in cur: pk[cur["Package"]] = cur
seen, todo = set(), list(roots)
while todo:
    p = todo.pop()
    if p in seen or p in skip: continue
    if p not in pk:
        # provides?
        cand = [n for n, d in pk.items() if re.search(rf"(^|, ){re.escape(p)}( |,|$)", d.get("Provides", ""))]
        if not cand: print("missing", p); continue
        p = cand[0]
    seen.add(p)
    for dep in pk[p].get("Depends", "").split(","):
        dep = dep.strip().split(" ")[0].split("|")[0]
        if dep: todo.append(dep)
os.makedirs(out, exist_ok=True)
os.makedirs(f"dl/{arch}", exist_ok=True)
for p in sorted(seen):
    fn = pk[p]["Filename"]; dst = f"dl/{arch}/" + os.path.basename(fn)
    if not os.path.exists(dst):
        subprocess.run(["curl","-sfL","-o",dst,"https://packages-cf.termux.dev/apt/termux-main/" + fn], check=True)
    subprocess.run(["dpkg-deb", "-x", dst, out], check=True)
    print(p, pk[p]["Version"])
