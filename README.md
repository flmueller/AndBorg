# AndBorg

**AndBorg** ist eine Android-App (Kotlin/Compose), die Handy-Dateien automatisch mit **Borg 1.x** über SSH auf einen Borg-Server sichert.
**borg und ssh sind in der App enthalten**, Termux ist nicht nötig. **Shizuku** ist optional: Damit werden zusätzlich
`Android/data` und `Android/obb` gesichert.

## Installation

Die APK gibt es unter [Releases](https://github.com/flmueller/AndBorg/releases) (Android 10+, arm64).

## Einrichtung

Auf der Seite **Status** stehen die Schritte:

1. **Zugriff auf alle Dateien** erlauben.
2. **Server und Passphrase** in den Einstellungen eintragen, z. B. `ssh://user@server:22/~/borg/handy`.
   Liegt borg auf dem Server nicht im Standard-PATH (typisch bei NAS-Systemen, Fehler `sh: borg: not found`),
   den Pfad aus `which borg` unter **Remote-Pfad zu borg** eintragen.
3. Den angezeigten **SSH-Schlüssel** auf dem Server in `~/.ssh/authorized_keys` eintragen
   (empfohlen: `command="borg serve --restrict-to-path …",restrict ssh-ed25519 …`).
4. **Verbindung testen**, oder **Neu anlegen** für ein neues Repository (repokey-blake2). Den angezeigten Papier-Schlüssel aufbewahren!
5. **Akku-Optimierung ausschalten**, damit geplante Backups im Hintergrund als Vordergrunddienst laufen dürfen.

Optional **Shizuku**: Shizuku starten → in AndBorg „Shizuku erlauben“ → in den Einstellungen „Android/data + Android/obb“ einschalten.

## Ablauf eines Backups

`borg create` (Ordner) → mit Shizuku `tar` (als Shell-Benutzer) | `borg import-tar` → `borg prune` pro Archivtyp → `borg compact`.
Archive: `<präfix>-files-JJJJ-MM-TT_HH-MM` und `<präfix>-appdata-…`. Läuft über WorkManager als Vordergrunddienst
(Typ dataSync), manuell oder nach Zeitplan. Ergebnis und Log stehen im Verlauf und in einer Benachrichtigung.

## Wie borg in die App kommt

Die Programme stammen aus den offiziellen Termux-Paketen (borgbackup 1.4.5, python 3.14, openssh und Bibliotheken):

- `runtime/tools/fetch.py <arch> stage-<arch>` lädt die Pakete samt Abhängigkeiten und entpackt sie.
- `runtime/tools/pack.py <arch> <abi>` erzeugt
  - `app/src/main/jniLibs/<abi>/lib{python,ssh,sshkeygen}_exec.so`: die ausführbaren Programme. Seit Android 10
    dürfen Apps nur Programme aus ihrem nativeLibraryDir starten, daher `useLegacyPackaging`/`extractNativeLibs`.
  - `app/src/main/assets/runtime-<abi>.zip`: Bibliotheken, Python-Standardbibliothek, borg, msgpack 1.0.8
    (reines Python, weil die Termux-Version für borg 1.4 zu neu ist) und packaging. Wird beim ersten Start nach `files/rt` entpackt.

Neu bauen (z. B. für ein borg-Update):

```
cd runtime
curl -sfL https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages -o dl/Packages-aarch64
curl -sfL https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-x86_64/Packages -o dl/Packages-x86_64
python3 tools/fetch.py aarch64 stage-aarch64 && python3 tools/pack.py aarch64 arm64-v8a
python3 tools/fetch.py x86_64 stage-x86_64 && python3 tools/pack.py x86_64 x86_64
```

(msgpack und packaging erwartet `pack.py` unter `runtime/dl/py/`, siehe Skript.)

## Code

- `runtime/BorgRuntime.kt`: Laufzeit entpacken, Umgebung (PYTHONHOME, LD_LIBRARY_PATH, BORG_RSH …), Prozesse starten, SSH-Schlüssel
- `runtime/Borg.kt`: info/init/list, verständliche Fehlerhinweise
- `work/BackupEngine.kt`: kompletter Backup-Ablauf mit Fortschritt (`--log-json`)
- `work/BackupWorker.kt`: WorkManager (Vordergrund), Zeitplan, „Jetzt sichern“, Abbrechen
- `shizuku/`: Shizuku-UserService (AIDL), der `tar` als Shell-Benutzer ausführt
- `data/`: Konfiguration (DataStore, Passphrase AES-GCM im Android Keystore), Verlauf
- `ui/`: Status, Einstellungen, Verlauf

Paketname: `de.flokke.borgapp` (aus der Entwicklungszeit beibehalten, damit bestehende Installationen ihre Einstellungen behalten).

Bauen: `./gradlew assembleDebug` (AGP 9.4, compileSdk 37, minSdk 29, targetSdk 36).

## Am PC wiederherstellen

borg speichert Besitzer und Rechte so, wie Android sie meldet: Benutzer ist die App-ID auf dem Handy (z. B. `10354`),
Gruppe ist `media_rw` (`1023`), Rechte `drwxrws---`. Am PC gibt es diesen Benutzer nicht, beim Einhängen ist
der Zugriff daher zunächst verweigert. So geht es trotzdem:

```
# Einhängen, alle Dateien gehören dabei dem aktuellen Benutzer
borg mount -o uid=$(id -u),gid=$(id -g) REPO::ARCHIV /mnt/temp

# alternativ: Rechte beim Einhängen gar nicht prüfen
borg mount -o ignore_permissions REPO::ARCHIV /mnt/temp

# Wiederherstellen als normaler Benutzer: die Dateien gehören danach automatisch dir
borg extract REPO::ARCHIV storage/emulated/0/DCIM
```

In **Vorta**: Einstellungen → „Try to replace file permissions when mounting an archive“ einschalten
(setzt `-o umask=0277,uid=<eigene ID>`, die Dateien sind dann für dich nur lesbar).

## Release bauen

`./gradlew assembleRelease` erzeugt `app/build/outputs/apk/release/app-release.apk` (nur arm64). Signiert wird mit dem
Schlüssel aus `keystore.properties` im Projektordner (nicht im Repository):

```
storeFile=/pfad/zu/andborg-release.jks
storePassword=…
keyAlias=andborg
keyPassword=…
```

Die x86_64-Laufzeit liegt unter `app/src/debug/` und ist nur im Debug-Build (für den Emulator) enthalten.

## Lizenzen

Der Code von AndBorg steht unter der [MIT-Lizenz](LICENSE). Die mitgelieferte Fremdsoftware (borg, Python, OpenSSH …) behält ihre eigenen Lizenzen, siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Grenzen

- Private App-Daten (`/data/data/…`) sind ohne Root nicht erreichbar, auch nicht mit Shizuku.
- Wiederherstellen geht derzeit nicht in der App (nur Archivliste); am PC mit `borg extract` oder `borg mount`.
- Die Berechtigung „Alle Dateien“ schließt eine Veröffentlichung im Play Store aus. Installation per APK ist kein Problem.
