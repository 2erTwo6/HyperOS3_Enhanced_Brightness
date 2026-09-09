package de.robv.android.xposed;
import java.io.File;
public class XSharedPreferences {
    public XSharedPreferences(String packageName) {}
    public XSharedPreferences(String packageName, String prefFileName) {}
    public XSharedPreferences(File prefFile) {}
    public void reload() {}
    public int getInt(String key, int def) { return def; }
    public String getString(String key, String def) { return def; }
    public File getFile() { return null; }
}