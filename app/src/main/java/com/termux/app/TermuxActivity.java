package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.models.ToolItem;
import com.termux.app.models.ToolStore;
import com.termux.app.terminal.ToolsAdapter;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** Adapter backing the quick tools drawer grid. */
    ToolsAdapter mToolsAdapter;

    /** Whether the quick tools grid is in manage mode (tap = edit, long-press = drag). */
    boolean mToolsManageMode = false;

    /** ItemTouchHelper driving quick tools drag-reorder. */
    ItemTouchHelper mItemTouchHelper;

    /** Toggle checks currently in flight, mapped to the refresh round that started them. */
    private final java.util.Map<String, Long> mToggleChecksInFlight = new java.util.HashMap<>();

    /** Per-tool check tokens. Bumped when a tool's fresh check must invalidate an older
     *  in-flight result. Per tool (not global) so rechecking one tool does not discard
     *  the results of other tools that are still running. */
    private final java.util.Map<String, Long> mToggleCheckToken = new java.util.HashMap<>();

    /** Predictive back callback (Android 13+). */
    private androidx.activity.OnBackPressedCallback mBackCallback;

    /** Drawer edge-swipe zone width in pixels (applied to both left and right drawers). */
    private int mDrawerEdgeSizePx;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            mNavBarHeight = sysBars.bottom;
            // Do NOT set padding here: the root view's fitsSystemWindows already
            // handles status/nav bar spacing. Only track nav bar height for
            // FullScreenWorkAround and other layout calculations.
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        applyFullScreenMode();

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        setQuickToolsDrawerView();

        setDrawerEdgeTouchRestriction();

        setupPredictiveBack();

        registerForContextMenu(mTerminalView);
        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        // Re-apply fullscreen mode in case settings changed while away.
        applyFullScreenMode();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        // Drop pending toggle re-checks: their callbacks reference this activity and the
        // tools adapter, so leaving them queued keeps the activity alive and lets a late
        // task touch a destroyed view hierarchy.
        mMainHandler.removeCallbacksAndMessages(null);

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        RecyclerView sessionsRecyclerView = findViewById(R.id.terminal_sessions_list);
        sessionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        sessionsRecyclerView.setAdapter(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    /** Set up the right-side quick tools drawer (icon grid, add/edit/delete, run command). */
    private void setQuickToolsDrawerView() {
        RecyclerView toolsRecyclerView = findViewById(R.id.tools_recycler_view);
        toolsRecyclerView.setLayoutManager(new GridLayoutManager(this, 4));

        List<ToolItem> tools = ToolStore.load(this);
        ToolsAdapter adapter = new ToolsAdapter(tools, new ToolsAdapter.OnToolClickListener() {
            @Override
            public void onToolClick(ToolItem tool) {
                // In manage mode a tap edits the tool instead of running it.
                if (mToolsManageMode) {
                    showToolEditDialog(tool);
                    return;
                }

                // Mirror picker tool opens its dialog instead of running a command.
                if (ToolItem.MIRROR_SWITCH_COMMAND.equals(tool.command)) {
                    getDrawer().closeDrawers();
                    showMirrorSwitchDialog();
                    return;
                }

                runDrawerCommand(tool.command);
                getDrawer().closeDrawers();

                // Clear the badge, then re-check once the toggle has run; the late
                // re-check catches slow starts (e.g. yingshi waits up to 10s).
                if (tool.isToggle()) {
                    // Grace period applies only when starting something: a service that is
                    // being stopped reports "off" almost immediately and must show red, not
                    // wait out the boot window.
                    boolean wasOn = tool.state != null && tool.state == 1;
                    tool.state = null;
                    tool.startGraceUntil = wasOn ? 0 : System.currentTimeMillis() + TOGGLE_START_GRACE_MS;
                    mToolsAdapter.notifyDataSetChanged();
                    // Forced recheck invalidates any result still in flight from before the
                    // toggle ran, so a stale pre-toggle state cannot land after the reset.
                    // Look the tool up again when the timer fires: reopening the drawer
                    // reloads the list and replaces the instance captured here.
                    mMainHandler.postDelayed(() -> {
                        ToolItem live = findLiveTool(tool);
                        if (live != null) refreshToggleStateForced(live);
                    }, 800);
                    mMainHandler.postDelayed(() -> {
                        ToolItem live = findLiveTool(tool);
                        if (live != null) refreshToggleState(live);
                    }, 3000);
                    // Repaint only (no command, no process) once the grace period ends, so
                    // a still-off badge turns red instead of sitting grey forever.
                    if (tool.startGraceUntil > 0) {
                        mMainHandler.postDelayed(() -> {
                            if (mToolsAdapter != null) mToolsAdapter.notifyDataSetChanged();
                        }, TOGGLE_START_GRACE_MS);
                    }
                }
            }

            @Override
            public void onToolLongClick(ToolItem tool) {
                // Long-press is reserved for dragging inside manage mode; in normal
                // mode it does nothing (editing lives behind the manage button).
            }
        }, () -> {
            // Drag-to-reorder persistence: warn on failure, the order would silently
            // revert on the next drawer open otherwise.
            if (!ToolStore.save(this, mToolsAdapter.getTools()))
                Logger.showToast(this, getString(R.string.tools_save_failed), false);
        });
        toolsRecyclerView.setAdapter(adapter);
        mToolsAdapter = adapter;
        // Drag-to-reorder tools, started via the title-bar toggle button (reorder mode)
        mItemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                        @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN
                    | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return mToolsAdapter.moveItem(viewHolder.getAdapterPosition(),
                    target.getAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Not used; no swipe actions.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // Drag only inside manage mode; normal-mode taps keep running tools.
                return mToolsManageMode;
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                // Lift feedback while dragging.
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.setTranslationZ(16f);
                    viewHolder.itemView.setScaleX(1.08f);
                    viewHolder.itemView.setScaleY(1.08f);
                    viewHolder.itemView.setAlpha(0.9f);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // Reset the visual lift.
                viewHolder.itemView.setTranslationZ(0f);
                viewHolder.itemView.setScaleX(1f);
                viewHolder.itemView.setScaleY(1f);
                viewHolder.itemView.setAlpha(1f);
                // Drag finished (dropped or cancelled): persist the new order.
                mToolsAdapter.onDragSettled();
            }
        });
        mItemTouchHelper.attachToRecyclerView(toolsRecyclerView);

        findViewById(R.id.tools_reorder_button).setOnClickListener(v -> toggleToolsManageMode());
        findViewById(R.id.add_tool_button).setOnClickListener(v -> showToolEditDialog(null));

        // Reload tools when the right drawer opens: the list is otherwise only loaded
        // in onCreate, so external edits to ZDY.json would be clobbered by the next
        // in-app save (drag/edit). Also refreshes toggle badges on every open.
        getDrawer().addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (drawerView.getId() != R.id.right_drawer) return;
                if (mToolsManageMode || mToolsAdapter == null) return;
                List<ToolItem> fresh = ToolStore.load(TermuxActivity.this);
                List<ToolItem> current = mToolsAdapter.getTools();

                // Carry known badge states over by (id, checkCommand) so reloading the
                // list does not blank every badge until the checks finish.
                java.util.Map<String, Integer> previousStates = new java.util.HashMap<>();
                java.util.Map<String, String> previousChecks = new java.util.HashMap<>();
                java.util.Map<String, Long> previousGrace = new java.util.HashMap<>();
                for (ToolItem t : current) {
                    if (!t.isToggle()) continue;
                    if (t.state != null) {
                        previousStates.put(t.id, t.state);
                        previousChecks.put(t.id, t.checkCommand);
                    }
                    // A service that is still booting has no state yet (the toggle sets
                    // state to null), so the grace window has to be carried over on its
                    // own - otherwise reopening the drawer paints the badge red while
                    // the service is still starting up.
                    if (t.startGraceUntil > 0) {
                        previousGrace.put(t.id, t.startGraceUntil);
                        previousChecks.put(t.id, t.checkCommand);
                    }
                }

                current.clear();
                current.addAll(fresh);
                for (ToolItem t : current) {
                    if (!t.isToggle()) continue;
                    // Reuse the old state only if the check command itself is unchanged,
                    // otherwise the badge would describe a different command.
                    if (t.checkCommand.equals(previousChecks.get(t.id))) {
                        t.state = previousStates.get(t.id);
                        // Keep the start-up grace period too, or reopening the drawer right
                        // after starting a service would show red instead of "still starting".
                        Long grace = previousGrace.get(t.id);
                        if (grace != null) t.startGraceUntil = grace;
                    }
                }
                mToolsAdapter.notifyDataSetChanged();
                refreshAllToggleStates();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                if (drawerView.getId() != R.id.right_drawer) return;
                // Leaving manage mode on close: a stale mode would turn the next tap
                // into an unexpected edit dialog.
                if (mToolsManageMode) toggleToolsManageMode();
            }
        });

        // Initial state check for toggle tools.
        refreshAllToggleStates();
    }

    /** Shared main-thread handler for delayed toggle re-checks. Reused instead of
     *  allocating one per tap, and cleared in onDestroy so pending posts do not hold the
     *  activity after it is gone. */
    private final android.os.Handler mMainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    /** How long after tapping a toggle an "off" result is shown as unknown instead of
     *  failed, covering services that need several seconds to finish starting. */
    private static final long TOGGLE_START_GRACE_MS = 10000;

    /** Hard cap for a single toggle check. The check itself runs under
     *  `timeout -k 1 2`, so it normally ends after ~2s; this is only the backstop for
     *  the case where `timeout` itself is broken and the command would run forever. */
    private static final long TOGGLE_CHECK_TIMEOUT_MS = 4000;

    /** Re-check one toggle tool's state (runs checkCommand off the UI thread). */
    private void refreshToggleState(ToolItem tool) {
        refreshToggleState(tool, false);
    }

    /** Re-check after the user toggled something: invalidate this tool's older in-flight
     *  result so a pre-toggle state cannot land, without disturbing other tools. */
    private void refreshToggleStateForced(ToolItem tool) {
        synchronized (mToggleChecksInFlight) {
            Long token = mToggleCheckToken.get(tool.id);
            mToggleCheckToken.put(tool.id, token == null ? 1L : token + 1L);
        }
        refreshToggleState(tool, true);
    }

    /** Find the tool instance that is currently displayed in the drawer.
     *  The list is reloaded from disk every time the drawer opens, which replaces the
     *  ToolItem instances. A reference captured earlier therefore may no longer be the
     *  one on screen, and writing a check result into it would be invisible. */
    private ToolItem findLiveTool(ToolItem tool) {
        if (mToolsAdapter == null || tool == null || tool.id == null) return null;
        List<ToolItem> tools = mToolsAdapter.getTools();
        for (int i = 0; i < tools.size(); i++) {
            ToolItem t = tools.get(i);
            if (tool.id.equals(t.id)) return t;
        }
        return null;
    }

    /** @param force start a check even if one for this tool is already running */
    private void refreshToggleState(ToolItem tool, boolean force) {
        if (!tool.isToggle()) return;
        final long token;
        synchronized (mToggleChecksInFlight) {
            Long tokenObj = mToggleCheckToken.get(tool.id);
            token = tokenObj == null ? 0L : tokenObj;
            // Same token still in flight means the running check is already this fresh.
            Long running = mToggleChecksInFlight.get(tool.id);
            if (!force && running != null && running.longValue() == token) return;
            mToggleChecksInFlight.put(tool.id, token);
        }
        final String checkCommand = tool.checkCommand;
        new Thread(() -> {
            Integer state = runToggleCheck(checkCommand);
            synchronized (mToggleChecksInFlight) {
                // Only clear the slot if this attempt still owns it.
                if (Long.valueOf(token).equals(mToggleChecksInFlight.get(tool.id)))
                    mToggleChecksInFlight.remove(tool.id);
                Long tokenObj = mToggleCheckToken.get(tool.id);
                long currentToken = tokenObj == null ? 0L : tokenObj;
                if (token != currentToken) return; // superseded by a forced recheck
            }
            // Posted through mMainHandler, not Activity.runOnUiThread(): the latter uses
            // a private handler that onDestroy cannot clear, so a late check result
            // would still touch the view hierarchy of a destroyed activity.
            mMainHandler.post(() -> {
                if (mToolsAdapter == null) return;
                // The drawer list gets reloaded on every open, so the instance captured
                // when this check started may already have been replaced. Write the
                // result into the instance that is on screen now, otherwise the badge
                // would stay stuck on "unknown" for the rest of the session.
                ToolItem live = findLiveTool(tool);
                if (live == null) return;
                // The command may have been edited while the check was running.
                if (!checkCommand.equals(live.checkCommand)) return;
                live.state = state;
                mToolsAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    /** Re-check all toggle tools. */
    private void refreshAllToggleStates() {
        if (mToolsAdapter == null) return;
        synchronized (mToggleChecksInFlight) {
            // Bump a tool's token only when no check for it is currently running: a check
            // still in flight is already this fresh, so reusing it (instead of bumping)
            // keeps rapid drawer reopens from stacking processes.
            for (ToolItem t : mToolsAdapter.getTools()) {
                if (!t.isToggle()) continue;
                if (mToggleChecksInFlight.containsKey(t.id)) continue;
                Long token = mToggleCheckToken.get(t.id);
                mToggleCheckToken.put(t.id, token == null ? 1L : token + 1L);
            }
        }
        for (ToolItem t : mToolsAdapter.getTools()) {
            if (t.isToggle()) refreshToggleState(t);
        }
    }

    /** Run a toggle tool's check command outside the terminal; exit code 0 = on. */
    private Integer runToggleCheck(String cmd) {
        try {
            // coreutils `timeout` enforces the limit inside the shell and kills the whole
            // process group, so a piped command (e.g. "ls ... | head -1") cannot leave
            // jq/curl orphans behind. Doing it here instead of with Process.destroy() also
            // avoids Process.pid()/destroyForcibly(), which need API 26 while this APK
            // supports API 24. Exit code 124 means the timeout fired.
            ProcessBuilder pb = new ProcessBuilder(
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/timeout",
                "-k", "1", "2",
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash", "-c", cmd)
                // Discard output so a noisy check can't block on the 64KB pipe.
                .redirectOutput(new java.io.File("/dev/null"))
                .redirectError(new java.io.File("/dev/null"));
            // Checks run outside a session: app env lacks the Termux bin dir in
            // PATH and HOME, so set both.
            String path = pb.environment().get("PATH");
            pb.environment().put("PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin"
                + (path != null && !path.isEmpty() ? ":" + path : ""));
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            Process p = pb.start();
            // Process.waitFor(long, TimeUnit) was added in API 26 (same batch as
            // destroyForcibly()/isAlive()) and core library desugaring does not cover
            // java.lang.Process, so on API 24/25 it would throw NoSuchMethodError.
            // That is an Error, not an Exception, and an uncaught Error in a thread
            // takes the whole app process down. Poll exitValue() instead - it is API 1.
            // The wait is slightly longer than the tool's own limit so it wins the race
            // and we simply read its exit code instead of having to kill anything.
            long deadline = System.currentTimeMillis() + TOGGLE_CHECK_TIMEOUT_MS;
            int exit;
            while (true) {
                try {
                    exit = p.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    // The check has not finished yet; fall through to the timeout check.
                }
                if (System.currentTimeMillis() >= deadline) {
                    p.destroy();
                    return null;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroy();
                    return null;
                }
            }
            // 124 = timed out. 125+ = timeout itself failed (missing binary, not
            // executable); that is "unknown", not "off", so don't paint the badge red.
            if (exit == 124 || exit >= 125) return null;
            return exit == 0 ? 1 : 0;
        } catch (Throwable e) {
            // Throwable, not Exception: a linkage error (NoSuchMethodError from an API
            // level we do not support) must degrade to "state unknown", never crash.
            return null;
        }
    }


    /** Run a command in the current session, or a fresh one when the current is busy
     *  in a TUI/fullscreen app (same routing as quick tools). */
    private void runDrawerCommand(String command) {
        // An empty command would paste nothing but still send the trailing CR, which
        // presses Enter in whatever shell or TUI is in front (accidental confirm/cancel).
        if (command == null || command.isEmpty()) return;
        TerminalSession session = getCurrentSession();
        boolean sessionBusy = session != null && session.isRunning()
            && session.getEmulator() != null && session.getEmulator().isAlternateBufferActive();

        // A dead session ("press Enter to close") would silently swallow the command,
        // so it needs a fresh session just like a busy TUI one.
        if (session == null || !session.isRunning() || sessionBusy) {
            mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            TerminalSession newSession = getCurrentSession();
            // addNewSession() silently bails out when the session limit is reached, leaving
            // the previous (busy/dead) session current. Writing into it would dump the
            // command into a TUI or a dead shell, so only paste when a usable session that
            // is not the one we tried to escape is actually available.
            if (newSession == session) return;
            if (newSession != null && newSession.isRunning()
                && newSession.getEmulator() != null) {
                newSession.getEmulator().paste(command);
                newSession.write("\r");
            }
        } else {
            session.getEmulator().paste(command);
            session.write("\r");
        }
    }

    /** Toggle manage mode for the quick tools grid (tap = edit, long-press = drag). */
    private void toggleToolsManageMode() {
        mToolsManageMode = !mToolsManageMode;

        TextView title = findViewById(R.id.tools_drawer_title);
        ImageButton manageButton = findViewById(R.id.tools_reorder_button);
        // "Add tool" is an editing action, so it only shows in manage mode; in
        // normal mode it would be a stray button below the grid.
        MaterialButton addToolButton = findViewById(R.id.add_tool_button);
        if (mToolsManageMode) {
            title.setText(R.string.tools_manage_mode_hint);
            manageButton.setImageResource(R.drawable.ic_done);
            manageButton.setAlpha(1f);
            addToolButton.setVisibility(View.VISIBLE);
            // The button now finishes the mode, so say so instead of repeating
            // "manage tools", which is what it did before.
            manageButton.setContentDescription(getString(R.string.tools_manage_mode_exit));
            manageButton.setColorFilter(
                ContextCompat.getColor(this, android.R.color.holo_blue_light));
            // No toast: the drawer title already shows the mode and how to leave it,
            // and the button turning into a blue check mark says the same thing.
            // Popping the identical sentence on top of that was just noise.
        } else {
            title.setText(R.string.tools_drawer_title);
            manageButton.setImageResource(R.drawable.ic_reorder);
            manageButton.setAlpha(0.6f);
            addToolButton.setVisibility(View.GONE);
            manageButton.setContentDescription(getString(R.string.tools_manage_mode));
            manageButton.clearColorFilter();
            // Persist the (possibly new) order on exit.
            if (!ToolStore.save(this, mToolsAdapter.getTools()))
                Logger.showToast(this, getString(R.string.tools_save_failed), false);
        }
    }

    /** Show a dialog to pick an apt mirror, then switch sources.list and refresh the index. */
    private void showMirrorSwitchDialog() {
        // URLs verified reachable (200) on device 2026-11: aliyun has NO /apt/ segment.
        final String[][] mirrors = {
            {"官方源 (termux.dev)", "https://packages.termux.dev/apt/termux-main"},
            {"清华源 (tuna)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"},
            {"中科大源 (ustc)", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"},
            {"阿里云源 (aliyun)", "https://mirrors.aliyun.com/termux/termux-main"},
        };
        String[] names = new String[mirrors.length];
        for (int i = 0; i < mirrors.length; i++) names[i] = mirrors[i][0];

        // Match by host only (mirror paths differ), anchored on "deb" so comment
        // URLs don't confuse detection.
        String currentHost = null;
        try {
            StringBuilder data = new StringBuilder();
            // FileUtils instead of java.nio.file.Files: the latter needs API 26 and its
            // absence throws NoClassDefFoundError, which "catch (Exception)" cannot catch.
            if (com.termux.shared.file.FileUtils.readTextFromFile(LOG_TAG,
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list",
                    java.nio.charset.StandardCharsets.UTF_8, data, true) == null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("deb\\s+https?://([^/ ]+)").matcher(data.toString());
                if (m.find()) currentHost = m.group(1);
            }
        } catch (Exception ignored) {
        }

        for (int i = 0; i < names.length; i++) {
            if (currentHost != null && mirrors[i][1].contains("://" + currentHost + "/")) {
                names[i] = names[i] + "  🟢";
            }
        }

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.tools_mirror_switch_title))
            .setItems(names, (dialog, which) -> {
                String url = mirrors[which][1];
                // Replace any termux-main URL regardless of path variant.
                String cmd = "sed -i 's|https://[^ ]*termux-main|" + url + "|g' $PREFIX/etc/apt/sources.list && echo \"已切换到: " + url + "\" && pkg update";
                // Route like every other tool: a busy TUI session would otherwise swallow
                // the command as input instead of running it in a shell.
                runDrawerCommand(cmd);
            })
            .setNegativeButton(R.string.tools_cancel, null)
            .show();
    }

    private void showToolEditDialog(@Nullable ToolItem tool) {
        boolean isNew = tool == null;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.tools_name_label);
        nameInput.setText(isNew ? "" : tool.name);
        container.addView(nameInput);

        EditText iconInput = new EditText(this);
        iconInput.setHint(R.string.tools_icon_label);
        iconInput.setText(isNew ? "▶" : tool.icon);
        container.addView(iconInput);

        EditText commandInput = new EditText(this);
        commandInput.setHint(R.string.tools_command_label);
        commandInput.setText(isNew ? "" : tool.command);
        commandInput.setSingleLine(false);
        commandInput.setMaxLines(4);
        container.addView(commandInput);

        EditText checkInput = new EditText(this);
        checkInput.setHint(R.string.tools_check_command_label);
        checkInput.setText(isNew || tool.checkCommand == null ? "" : tool.checkCommand);
        checkInput.setSingleLine(false);
        checkInput.setMaxLines(2);
        container.addView(checkInput);

        // Built-in tools like 切换源 route @…@ markers to dialog code instead of running
        // shell. Editing that marker would silently break the button, so the command
        // fields are frozen and the dialog says why instead of just ignoring taps.
        final boolean commandLocked = !isNew && ToolItem.isSpecialCommand(tool.command);
        if (commandLocked) {
            commandInput.setEnabled(false);
            checkInput.setEnabled(false);
            TextView lockHint = new TextView(this);
            lockHint.setTextSize(12);
            lockHint.setText(R.string.tools_command_locked_hint);
            lockHint.setPadding(0, pad / 2, 0, 0);
            container.addView(lockHint);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(isNew ? R.string.tools_new_title : R.string.tools_edit_title)
            .setView(container)
            // Null listeners: the buttons must NOT auto-dismiss, otherwise a validation
            // failure would close the dialog and silently discard everything typed.
            .setPositiveButton(R.string.tools_save, null)
            .setNeutralButton(R.string.tools_delete, null)
            .setNegativeButton(R.string.tools_cancel, null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = nameInput.getText().toString().trim();
                String icon = iconInput.getText().toString().trim();
                // Trailing whitespace/newlines would make paste() emit extra Enter presses
                // (it converts \n to \r) and run the command early, so trim the ends only.
                String command = commandInput.getText().toString().trim();
                String check = checkInput.getText().toString().trim();
                // A locked built-in command keeps its marker no matter what: only the
                // name and icon of such a tool are user-editable.
                if (commandLocked) {
                    command = tool.command;
                    check = tool.checkCommand == null ? "" : tool.checkCommand;
                }
                if (name.isEmpty() || command.isEmpty()) {
                    Logger.showToast(this, getString(R.string.tools_empty_error), false);
                    return; // keep the dialog open with the input intact
                }
                if (isNew) {
                    ToolItem t = new ToolItem(name, icon, command);
                    t.checkCommand = check;
                    mToolsAdapter.addTool(t);
                    // New tools are appended to the end; scroll so the user can see that it
                    // was actually added instead of staring at an unchanged grid.
                    RecyclerView grid = findViewById(R.id.tools_recycler_view);
                    if (grid != null) grid.smoothScrollToPosition(mToolsAdapter.getItemCount() - 1);
                } else {
                    // A changed check command invalidates the check that is running
                    // right now: its result would be thrown away on arrival and
                    // nothing would replace it, leaving the badge grey until the
                    // drawer is opened again.
                    boolean checkChanged = !check.equals(tool.checkCommand);
                    tool.name = name;
                    tool.icon = icon;
                    tool.command = command;
                    tool.checkCommand = check;
                    tool.state = null;
                    tool.startGraceUntil = 0;
                    // Clear any in-flight bookkeeping when a tool stops being a toggle
                    // or its check command changed, so a stale entry cannot linger in
                    // the maps and the next refresh starts a check of the new command.
                    if (!tool.isToggle() || checkChanged) {
                        synchronized (mToggleChecksInFlight) {
                            mToggleChecksInFlight.remove(tool.id);
                            mToggleCheckToken.remove(tool.id);
                        }
                    }
                    mToolsAdapter.notifyDataSetChanged();
                }
                if (!ToolStore.save(this, mToolsAdapter.getTools()))
                    Logger.showToast(this, getString(R.string.tools_save_failed), false);
                refreshAllToggleStates();
                dialog.dismiss();
            });

            // Delete needs its own confirmation: a mis-tap would destroy the tool and its
            // command, which can be a long one-liner that is painful to retype.
            // A brand new tool has nothing to delete, so hide the button instead of
            // leaving a dead one on screen.
            if (isNew)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (isNew) return;
                new AlertDialog.Builder(this)
                    .setTitle(R.string.tools_delete)
                    .setMessage(getString(R.string.tools_delete_confirm, tool.name))
                    .setPositiveButton(R.string.tools_delete, (cd, cw) -> {
                        mToolsAdapter.removeTool(tool);
                        // Drop any in-flight check slot for the removed tool.
                        synchronized (mToggleChecksInFlight) {
                            mToggleChecksInFlight.remove(tool.id);
                            mToggleCheckToken.remove(tool.id);
                        }
                        if (!ToolStore.save(this, mToolsAdapter.getTools()))
                            Logger.showToast(this, getString(R.string.tools_save_failed), false);
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.tools_cancel, null)
                    .show();
            });
        });

        dialog.show();
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        // Legacy fallback for pre-Android 13; OnBackPressedCallback handles the rest.
        handleBackPressed();
    }

    /** Handle back press: close open drawer first, then finish. */
    private void handleBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawer(Gravity.LEFT);
        } else if (getDrawer().isDrawerOpen(Gravity.RIGHT)) {
            getDrawer().closeDrawer(Gravity.RIGHT);
        } else {
            finishActivityIfNotFinishing();
        }
    }

    /** Register the predictive-back callback (Android 13+) and sync with drawer state. */
    private void setupPredictiveBack() {
        androidx.activity.OnBackPressedCallback callback =
            new androidx.activity.OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    handleBackPressed();
                    updateBackCallbackState();
                }
            };
        getOnBackPressedDispatcher().addCallback(this, callback);

        // Keep the callback enabled only when a drawer is open, so the system
        // predictive-back animation plays correctly for exit (no drawer open).
        getDrawer().addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                updateBackCallbackState();
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                updateBackCallbackState();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                updateBackCallbackState();
            }
        });

        // Store reference for state updates
        mBackCallback = callback;
        updateBackCallbackState();
    }

    private void updateBackCallbackState() {
        if (mBackCallback != null) {
            boolean drawerOpen = getDrawer().isDrawerOpen(Gravity.LEFT)
                || getDrawer().isDrawerOpen(Gravity.RIGHT);
            mBackCallback.setEnabled(drawerOpen);
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    /**
     * Compute the drawer edge-swipe zone: 30% of the drawer width (clamped to 80-160dp),
     * re-applied from the root view's onGlobalLayout() since DrawerLayout resets edge
     * sizes on every layout pass (max(default, gestureInsets)).
     */
    private void setDrawerEdgeTouchRestriction() {
        float density = getResources().getDisplayMetrics().density;
        int minPx = (int) (80 * density);
        int maxPx = (int) (160 * density);
        // drawer width is 240dp (left) / 260dp (right); use 30% of the average
        int drawerWidthPx = (int) (250 * density);
        mDrawerEdgeSizePx = Math.min(maxPx, Math.max(minPx, (int) (drawerWidthPx * 0.30f)));
        applyDrawerEdgeSize();
    }

    /**
     * Apply the `fullscreen-mode` property:
     * `no` (default) - nothing, `status` - hide status bar only,
     * `immersive` - hide status + navigation bars, reveal transiently by edge swipe.
     */
    private void applyFullScreenMode() {
        String mode = mProperties.getFullScreenMode();
        if (mode == null || mode.isEmpty()) return;

        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(decorView);
        if (controller == null) return;

        boolean flagFullscreen =
            (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;

        switch (mode) {
            case com.termux.shared.termux.settings.properties.TermuxPropertyConstants.IVALUE_FULL_SCREEN_MODE_STATUS:
                controller.hide(WindowInsetsCompat.Type.statusBars());
                break;
            case com.termux.shared.termux.settings.properties.TermuxPropertyConstants.IVALUE_FULL_SCREEN_MODE_IMMERSIVE:
                controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
                break;
            default:
                // "no": restore the bars, unless the legacy `fullscreen=true` property is
                // in effect - FLAG_FULLSCREEN hides the status bar by itself and calling
                // show() here would undo it (the two settings would fight each other).
                if (!flagFullscreen) controller.show(WindowInsetsCompat.Type.systemBars());
                break;
        }
    }

    /**
     * Enlarge the drawer edge-swipe zones so a swipe from a good part of the screen edge
     * opens the drawer reliably. The default zone is tiny and overlaps the system
     * back-gesture area on gesture navigation, so swipes meant for the drawer end up
     * triggering "back". DrawerLayout resets edge sizes on every layout pass
     * (max(default, gestureInsets)), so we re-apply the size from the root view's
     * existing onGlobalLayout() flow. ViewDragHelper.setEdgeSize() is a public API; the
     * dragger fields of DrawerLayout are private, hence the small reflection accessor.
     */
    public void applyDrawerEdgeSize() {
        if (mDrawerEdgeSizePx <= 0) return;
        setDraggerEdgeSize("mLeftDragger", mDrawerEdgeSizePx);
        setDraggerEdgeSize("mRightDragger", mDrawerEdgeSizePx);
    }

    /** Cached DrawerLayout dragger Field lookups; getDeclaredField scans class metadata
     *  and is expensive, while onGlobalLayout() calls this on every layout pass. */
    private static final java.util.Map<String, java.lang.reflect.Field> sDraggerFields = new java.util.HashMap<>();
    /** Set once a reflective access fails so we neither retry nor spam logcat every layout. */
    private static boolean sDraggerFieldsUnavailable = false;

    private void setDraggerEdgeSize(String fieldName, int edgePx) {
        if (sDraggerFieldsUnavailable) return;
        try {
            java.lang.reflect.Field f = sDraggerFields.get(fieldName);
            if (f == null) {
                f = DrawerLayout.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                sDraggerFields.put(fieldName, f);
            }
            Object dragger = f.get(getDrawer());
            if (dragger instanceof androidx.customview.widget.ViewDragHelper) {
                // Re-applied on every layout pass on purpose: DrawerLayout resets the edge
                // size to max(default, gestureInsets) during its own layout, so caching
                // "already applied" would silently disable the enlarged swipe zone.
                ((androidx.customview.widget.ViewDragHelper) dragger).setEdgeSize(edgePx);
            }
        } catch (Exception e) {
            sDraggerFieldsUnavailable = true;
            Logger.logError(LOG_TAG, "setDraggerEdgeSize failed, edge customization disabled: " + e.getMessage());
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, true, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
