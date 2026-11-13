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

    public String id;
    public String name;
    /** Emoji or single character shown as the icon. */
    public String icon;
    /** Shell command to run in the current session. */
    public String command;

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

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put(KEY_ID, id);
        o.put(KEY_NAME, name);
        o.put(KEY_ICON, icon);
        o.put(KEY_COMMAND, command);
        return o;
    }

    public static ToolItem fromJson(JSONObject o) throws JSONException {
        return new ToolItem(
            o.getString(KEY_ID),
            o.getString(KEY_NAME),
            o.optString(KEY_ICON, "▶"),
            o.getString(KEY_COMMAND));
    }

    /** Serialize a list of tools to a JSON string. */
    public static String listToJson(List<ToolItem> tools) throws JSONException {
        JSONArray arr = new JSONArray();
        for (ToolItem t : tools) arr.put(t.toJson());
        return arr.toString(2);
    }

    /** Parse a JSON string back to a list of tools. Returns null on error. */
    public static List<ToolItem> listFromJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<ToolItem> tools = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                tools.add(ToolItem.fromJson(arr.getJSONObject(i)));
            }
            return tools;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Default tools used on first run. */
    public static List<ToolItem> defaults() {
        List<ToolItem> tools = new ArrayList<>();
        tools.add(new ToolItem("同步索引", "⬇", "pkg update"));
        tools.add(new ToolItem("up全部包", "⬆", "pkg upgrade"));
        tools.add(new ToolItem("官方源", "🏛", "sed -i 's|https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main|https://packages.termux.dev/apt/termux-main|g' $PREFIX/etc/apt/sources.list && pkg update"));
        tools.add(new ToolItem("清华源", "🎓", "sed -i 's|https://packages.termux.dev/apt/termux-main|https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main|g' $PREFIX/etc/apt/sources.list && pkg update"));
        tools.add(new ToolItem("进程监视", "📊", "htop"));
        tools.add(new ToolItem("清屏", "🧹", "clear"));
        return tools;
    }
}
