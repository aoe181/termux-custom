package com.termux.app.terminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.models.ToolItem;

import java.util.Collections;
import java.util.List;

/**
 * Grid adapter for the quick tools drawer. Supports long-press-drag reordering
 * via {@link OnToolDragListener} callbacks driven by an ItemTouchHelper.
 */
public class ToolsAdapter extends RecyclerView.Adapter<ToolsAdapter.ViewHolder> {

    public interface OnToolClickListener {
        void onToolClick(ToolItem tool);
        void onToolLongClick(ToolItem tool);
    }

    /** Called after a drag-drop reorder completes; listener should persist the new order. */
    public interface OnToolDragListener {
        void onToolReordered();
    }

    private final List<ToolItem> mTools;
    private final OnToolClickListener mListener;
    private final OnToolDragListener mDragListener;

    public ToolsAdapter(List<ToolItem> tools, OnToolClickListener listener) {
        this(tools, listener, null);
    }

    public ToolsAdapter(List<ToolItem> tools, OnToolClickListener listener, OnToolDragListener dragListener) {
        mTools = tools;
        mListener = listener;
        mDragListener = dragListener;
    }

    /** Move a tool during drag (called by ItemTouchHelper.onMove). */
    public boolean moveItem(int fromPosition, int toPosition) {
        if (fromPosition < 0 || fromPosition >= mTools.size()
            || toPosition < 0 || toPosition >= mTools.size()) return false;
        // Stepwise adjacent swaps: a single Collections.swap() would corrupt the
        // order when the drag spans more than one position (fast drags).
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) Collections.swap(mTools, i, i + 1);
        } else {
            for (int i = fromPosition; i > toPosition; i--) Collections.swap(mTools, i, i - 1);
        }
        notifyItemMoved(fromPosition, toPosition);
        return true;
    }

    /** Called by ItemTouchHelper when the drag settles; persist order. */
    public void onDragSettled() {
        if (mDragListener != null) mDragListener.onToolReordered();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.tool_grid_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ToolItem tool = mTools.get(position);
        holder.icon.setText(tool.icon != null && !tool.icon.isEmpty() ? tool.icon : "▶");
        holder.name.setText(tool.name);
        if (tool.isToggle()) {
            holder.stateDot.setVisibility(View.VISIBLE);
            holder.stateDot.setTextColor(tool.state == null ? 0xFF9E9E9E
                : tool.state == 1 ? 0xFF4CAF50 : 0xFFF44336);
        } else {
            holder.stateDot.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> mListener.onToolClick(tool));
        holder.itemView.setOnLongClickListener(v -> {
            mListener.onToolLongClick(tool);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mTools.size();
    }

    public List<ToolItem> getTools() {
        return mTools;
    }

    public void addTool(ToolItem tool) {
        mTools.add(tool);
        notifyItemInserted(mTools.size() - 1);
    }

    public void removeTool(ToolItem tool) {
        int index = mTools.indexOf(tool);
        if (index >= 0) {
            mTools.remove(index);
            notifyItemRemoved(index);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView name;
        final TextView stateDot;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.tool_icon);
            name = itemView.findViewById(R.id.tool_name);
            stateDot = itemView.findViewById(R.id.tool_state_dot);
        }
    }
}
