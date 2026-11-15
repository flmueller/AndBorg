package de.flokke.borgapp.shizuku;

// Läuft über Shizuku als Shell-Benutzer und darf daher Android/data und Android/obb lesen.
interface IAppDataService {
    void destroy() = 16777114;

    /** Startet "tar args..." und liefert dessen Standardausgabe als Pipe. */
    ParcelFileDescriptor startTar(in String[] args) = 1;

    /** Wartet auf das Ende von tar: erste Zeile Exit-Code, danach Fehlermeldungen. */
    String finishTar() = 2;
}
