package com.termux.app.models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user-defined quick tool shown in the right-side tools drawer.
 * Each tool has a display name, an icon (emoji string) and a shell command
 * which is written to the current session's stdin when tapped.
 */
public class ToolItem {

    /** Mirror switch command marker: replaced at click time by an interactive mirror picker. */
    public static final String MIRROR_SWITCH_COMMAND = "@mirror_switch@";

    public String id;
    public String name;
    /** Emoji or single character shown as the icon. */
    public String icon;
    /** Shell command to run in the current session. */
    public String command;
    /** State-check command for "toggle" tools: exit code 0 = ON. Empty/null = normal command tool. */
    public String checkCommand;
    /** Runtime state cache for toggle tools: null = unknown, 0 = off, 1 = on. Not persisted. */
    public transient Integer state;

    /** Wall-clock deadline (ms) while a just-started service may still be booting. While it
     *  is in the future, an "off" check result is shown as unknown rather than a failure. */
    public transient long startGraceUntil;

    public ToolItem(String name, String icon, String command) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.icon = icon;
        this.command = command;
    }

    public ToolItem(String id, String name, String icon, String command) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.command = command;
    }

    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_ICON = "icon";
    private static final String KEY_COMMAND = "command";
    private static final String KEY_CHECK_COMMAND = "checkCommand";

    public boolean isToggle() {
        return checkCommand != null && !checkCommand.isEmpty();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put(KEY_ID, id);
        o.put(KEY_NAME, name);
        o.put(KEY_ICON, icon);
        o.put(KEY_COMMAND, command);
        if (isToggle()) o.put(KEY_CHECK_COMMAND, checkCommand);
        return o;
    }

    public static ToolItem fromJson(JSONObject o) throws JSONException {
        // Only name and command are essential; id/icon/checkCommand are repaired when
        // missing so a hand-edited file that lost one of them keeps the tool instead of
        // silently dropping it on the next save.
        String command = o.optString(KEY_COMMAND, "");
        String name = o.optString(KEY_NAME, "");
        if (command.isEmpty() && name.isEmpty())
            throw new JSONException("entry has neither name nor command");

        String id = o.optString(KEY_ID, "");
        if (id.isEmpty()) id = UUID.randomUUID().toString();

        ToolItem t = new ToolItem(id, name, o.optString(KEY_ICON, "▶"), command);
        t.checkCommand = o.optString(KEY_CHECK_COMMAND, "");
        return t;
    }

    /** Serialize a list of tools to a JSON string. */
    public static String listToJson(List<ToolItem> tools) throws JSONException {
        JSONArray arr = new JSONArray();
        for (ToolItem t : tools) arr.put(t.toJson());
        return arr.toString(2);
    }

    /** Parse a JSON string back to a list of tools. Returns null on error.
     *  Malformed entries are skipped instead of failing (and thus resetting) the whole list. */
    public static List<ToolItem> listFromJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<ToolItem> tools = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    tools.add(ToolItem.fromJson(arr.getJSONObject(i)));
                } catch (JSONException e) {
                    // Skip just this entry; a single bad tool must not wipe the user's config.
                }
            }
            return tools;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Default tools used on first run. */
    public static List<ToolItem> defaults() {        List<ToolItem> tools = new ArrayList<>();
        tools.add(new ToolItem("同步索引", "⬇", "pkg update"));
        tools.add(new ToolItem("up全部包", "⬆", "pkg upgrade"));
        tools.add(new ToolItem("切换源", "🔄", ToolItem.MIRROR_SWITCH_COMMAND));
        tools.add(new ToolItem("进程监视", "📊", "htop"));
        tools.add(new ToolItem("清屏", "🧹", "clear"));
        return tools;
    }
}
