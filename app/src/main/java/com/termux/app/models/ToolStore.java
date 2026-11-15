package com.termux.app.models;

import android.content.Context;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads and saves the user's tool list to $HOME/.termux/ZDY.json.
 */
public class ToolStore {

    private static final String LOG_TAG = "ToolStore";
    private static final String FILE_NAME = "ZDY.json";

    private static String getFilePath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/" + FILE_NAME;
    }

    /** Load tools from disk. First run returns defaults and persists them.
     *  A broken existing file is backed up (never silently overwritten) so the user's
     *  tools can be recovered by hand: every later save would otherwise destroy them. */
    public static List<ToolItem> load(Context context) {
        String path = getFilePath();
        List<ToolItem> tools = null;
        StringBuilder data = new StringBuilder();
        boolean fileExists = new java.io.File(path).exists();
        if (FileUtils.readTextFromFile(LOG_TAG, path, StandardCharsets.UTF_8, data, true) == null) {
            tools = ToolItem.listFromJson(data.toString());
        }
        if (tools == null) {
            tools = ToolItem.defaults();
            if (!fileExists) {
                save(context, tools);
            } else {
                // Corrupt file: keep a copy before anything overwrites it. Skipped when a
                // previous backup exists so repeated loads don't pile up copies.
                // Plain java.io on purpose: java.nio.file.Files needs API 26 and is not
                // covered by the desugaring lib, while this APK supports API 24.
                java.io.File backup = new java.io.File(path + ".corrupt");
                if (!backup.exists()) {
                    java.io.InputStream in = null;
                    java.io.OutputStream out = null;
                    try {
                        in = new java.io.FileInputStream(path);
                        out = new java.io.FileOutputStream(backup);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        Logger.logError(LOG_TAG, "ZDY.json is corrupt; saved a copy to " + backup);
                    } catch (Exception e) {
                        Logger.logError(LOG_TAG, "Failed to back up corrupt ZDY.json: " + e.getMessage());
                        // Do not leave a truncated backup behind: it would block a later retry.
                        if (backup.exists()) backup.delete();
                    } finally {
                        try {
                            if (in != null) in.close();
                        } catch (Exception ignored) {
                        }
                        try {
                            if (out != null) out.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return tools;
    }

    /** Persist tools to disk. Returns true on success.
     *  Written via a temp file + rename so an interrupted write (crash, full storage)
     *  cannot leave a half-written file that would later parse as corrupt. */
    public static boolean save(Context context, List<ToolItem> tools) {
        String path = getFilePath();
        String json;
        try {
            json = ToolItem.listToJson(tools);
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to serialize tools: " + e.getMessage());
            return false;
        }

        java.io.File tmp = new java.io.File(path + ".tmp");
        if (FileUtils.writeTextToFile(LOG_TAG, tmp.getAbsolutePath(), StandardCharsets.UTF_8,
                json, false) != null) {
            tmp.delete();
            return false;
        }
        // rename() is atomic on the same filesystem: readers see either the old file
        // or the complete new one, never a partial write.
        if (!tmp.renameTo(new java.io.File(path))) {
            // Fall back to a direct write if rename is not possible (e.g. odd storage).
            boolean ok = FileUtils.writeTextToFile(LOG_TAG, path, StandardCharsets.UTF_8,
                json, false) == null;
            tmp.delete();
            return ok;
        }
        return true;
    }
}
