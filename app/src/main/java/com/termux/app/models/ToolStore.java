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

    /** Load tools from disk. First run returns defaults and persists them. */
    public static List<ToolItem> load(Context context) {
        String path = getFilePath();
        List<ToolItem> tools = null;
        StringBuilder data = new StringBuilder();
        if (FileUtils.readTextFromFile(LOG_TAG, path, StandardCharsets.UTF_8, data, true) == null) {
            tools = ToolItem.listFromJson(data.toString());
        }
        if (tools == null) {
            tools = ToolItem.defaults();
            save(context, tools);
        }
        return tools;
    }

    /** Persist tools to disk. Returns true on success. */
    public static boolean save(Context context, List<ToolItem> tools) {
        try {
            return FileUtils.writeTextToFile(LOG_TAG, getFilePath(), StandardCharsets.UTF_8,
                ToolItem.listToJson(tools), false) == null;
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to serialize tools: " + e.getMessage());
            return false;
        }
    }
}
