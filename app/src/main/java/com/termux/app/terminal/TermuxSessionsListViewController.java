package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends RecyclerView.Adapter<TermuxSessionsListViewController.ViewHolder> {

    final TermuxActivity mActivity;
    final List<TermuxSession> mSessions;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        this.mActivity = activity;
        this.mSessions = sessionList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_terminal_sessions_list, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TermuxSession termuxSession = mSessions.get(position);
        if (termuxSession == null) {
            holder.title.setText("null session");
            return;
        }

        TerminalSession sessionAtRow = termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            holder.title.setText("null session");
            return;
        }

        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        if (shouldEnableDarkTheme) {
            holder.title.setBackground(
                ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected)
            );
        }

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        String numberPart = "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        holder.title.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            holder.title.setPaintFlags(holder.title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            holder.title.setPaintFlags(holder.title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = shouldEnableDarkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        holder.title.setTextColor(color);

        // Click: switch to session
        holder.itemView.setOnClickListener(v -> {
            TermuxSession clickedSession = mSessions.get(holder.getAdapterPosition());
            if (clickedSession != null) {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
                mActivity.getDrawer().closeDrawers();
            }
        });

        // Long click: rename / close menu
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            TermuxSession selectedSession = mSessions.get(pos);
            if (selectedSession == null) return false;
            showSessionMenu(selectedSession.getTerminalSession());
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mSessions.size();
    }

    /** Refresh the list display (call after sessions change). */
    public void notifyDataSetChangedSafely() {
        notifyDataSetChanged();
    }

    private void showSessionMenu(TerminalSession terminalSession) {
        String currentName = terminalSession.mSessionName;
        if (currentName == null || currentName.isEmpty())
            currentName = mActivity.getString(R.string.action_new_session);

        new AlertDialog.Builder(mActivity)
            .setTitle(currentName)
            .setItems(new CharSequence[]{
                    mActivity.getString(R.string.action_rename_session),
                    mActivity.getString(R.string.action_close_session)
                },
                (dialog, which) -> {
                    if (which == 0) {
                        mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
                    } else if (which == 1) {
                        closeSessionNow(terminalSession);
                    }
                })
            .setNegativeButton(R.string.tools_cancel, null)
            .show();
    }

    private void closeSessionNow(TerminalSession terminalSession) {
        com.termux.app.TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        // Force-remove bypasses TermuxSession.finish() which returns early when
        // the execution command is already in "failed" state (after SIGKILL).
        int index = service.forceRemoveTermuxSession(terminalSession);
        int size = service.getTermuxSessionsSize();

        mActivity.termuxSessionListNotifyUpdated();

        if (size == 0) {
            // No sessions left; close the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            // Switch to a neighbor session.
            if (index >= size) index = size - 1;
            if (index < 0) index = 0;
            com.termux.shared.termux.shell.command.runner.terminal.TermuxSession next =
                service.getTermuxSession(index);
            if (next != null) {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(next.getTerminalSession());
            }
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.session_title);
        }
    }
}
