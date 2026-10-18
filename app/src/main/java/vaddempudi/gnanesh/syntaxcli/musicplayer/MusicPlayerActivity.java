package vaddempudi.gnanesh.syntaxcli.musicplayer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.os.PowerManager;

import vaddempudi.gnanesh.syntaxcli.filemanager.FileLog;
import vaddempudi.gnanesh.syntaxcli.R;

public class MusicPlayerActivity extends AppCompatActivity {

    private static final int BG       = 0xFF000000;
    private static final int GREEN    = 0xFF00FF00;
    private static final int DIM      = 0xFF00AA00;
    private static final int NOW_PLAY = 0xFFFFFF00;

    private static final int REQ_AUDIO = 3001;
    private static final int REQ_BATTERY_OPT = 3002;

    public static final String ACTION_CTRL         = "vaddempudi.gnanesh.syntaxcli.MUSIC_CTRL";
    public static final String EXTRA_CMD           = "cmd";
    public static final String CMD_PLAY            = "play";
    public static final String CMD_PAUSE           = "pause";
    public static final String CMD_TOGGLE          = "toggle";
    public static final String CMD_NEXT            = "next";
    public static final String CMD_PREV            = "prev";
    public static final String CMD_OPEN_PLAYLIST   = "open_playlist";
    public static final String CMD_START_LAST      = "start_last";

    private static final int VIEW_LIBRARY  = 0;
    private static final int VIEW_PLAYLIST = 1;
    private static final int VIEW_PICKER   = 2;

    private static final int SORT_LATEST = 0;
    private static final int SORT_OLDEST = 1;
    private static final int SORT_AZ     = 2;
    private static final int SORT_ZA     = 3;

    private static final String PREFS = "music_player_prefs";
    private static final String KEY_PLAYLIST           = "active_playlist_ids";
    private static final String KEY_FILTER_ACTIVE      = "filter_active";
    private static final String KEY_LAST_VIEW_STATE    = "last_view_state";
    private static final String KEY_PLAYBACK_IS_PLAYLIST = "playback_is_playlist";
    private static final String KEY_PLAYBACK_LIST_IDS  = "playback_list_ids";
    private static final String KEY_PLAYBACK_LIST_KIND = "playback_list_kind";
    private static final String KEY_BATTERY_PROMPTED   = "battery_opt_prompted";

    private static final String KIND_PLAYLIST = "playlist";
    private static final String KIND_LIBRARY  = "library";

    private static long lastPlayedId = -1;
    private static String lastPlayedTitle = "";
    private static boolean isPausedStatic = false;

    public static volatile boolean alive = false;
    public static volatile boolean everOpened = false;

    public static class Track {
        long id;
        String title;
        String artist;
        String path;
        Uri contentUri;
        long durationMs;
        long dateAdded;
        long dateModified;
        long size;
    }

    private final List<Track> allTracks     = new ArrayList<>();
    private final List<Track> visibleTracks = new ArrayList<>();
    private final List<Long> activePlaylistIds = new ArrayList<>();
    private final Set<Long> pickBuffer = new HashSet<>();

    private boolean filterActive = false;
    private int viewState = VIEW_LIBRARY;
    private int sortMode = SORT_LATEST;

    private boolean searchOpen = false;
    private String  searchQuery = "";
    private boolean isPaused = false;

    private List<Track> currentPlaybackList = null;
    private boolean currentPlaybackIsPlaylist = false;

    private RecyclerView recycler;
    private TrackAdapter adapter;
    private LinearLayout topBar;
    private LinearLayout searchRow;
    private EditText     searchInput;
    private TextView     tvTitle;
    private TextView     emptyLabel;
    private ImageView    btnLeft;
    private ImageView    btnSearch;
    private ImageView    btnSort;
    private Button       btnAdd;
    private LinearLayout bottomBar;
    private TextView     tvNowPlaying;
    private ImageView    btnPrev;
    private ImageView    btnPlayPause;
    private ImageView    btnNext;
    private SeekBar      seekBar;

    private String pendingCmdFromLaunch = null;
    private String pendingIncomingPath = null;
    private boolean launchedForIncomingFile = false;
    private boolean pendingStartLast = false;

    private MediaPlayer player;
    private int playingIndex = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (dur > 0) seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                } catch (Exception ignored) { }
                ui.postDelayed(this, 500);
            }
        }
    };

    private final BroadcastReceiver ctrlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String cmd = intent.getStringExtra(EXTRA_CMD);
            handleControlCommand(cmd);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();
        loadPlaylist();

        int lastView = loadLastViewState();
        viewState = (lastView == VIEW_PLAYLIST && filterActive)
                ? VIEW_PLAYLIST : VIEW_LIBRARY;

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(ctrlReceiver, new IntentFilter(ACTION_CTRL));

        alive = true;
        everOpened = true;

        boolean startHidden = getIntent() != null
                && getIntent().getBooleanExtra("start_hidden", false);
        if (startHidden) pendingStartLast = true;

        handleIncomingIntent(getIntent());

        String pendingCmd = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_CMD) : null;
        if (pendingCmd != null) {
            pendingCmdFromLaunch = pendingCmd;
            getIntent().removeExtra(EXTRA_CMD);
        }

        if (hasAudioPermission()) {
            loadTracks();
        } else {
            requestAudioPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeRequestBatteryOptimizationExemption();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        alive = false;
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(ctrlReceiver);
        } catch (Exception ignored) { }
        if (isFinishing()) releasePlayer();
    }

    @Override
    public void onBackPressed() {
        if (searchOpen) { closeSearch(); return; }
        switch (viewState) {
            case VIEW_PICKER:
                pickBuffer.clear();
                viewState = filterActive ? VIEW_PLAYLIST : VIEW_LIBRARY;
                refreshList();
                updateToolbar();
                return;
            case VIEW_PLAYLIST:
                viewState = VIEW_LIBRARY;
                refreshList();
                updateToolbar();
                saveLastViewState();
                return;
            default:
                if (launchedForIncomingFile) {
                    finish();
                    return;
                }
                super.onBackPressed();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(BG);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        btnLeft = new ImageView(this);
        btnLeft.setImageResource(R.drawable.ic_menu);
        btnLeft.setColorFilter(GREEN);
        btnLeft.setOnClickListener(v -> onLeftIconTapped());
        topBar.addView(btnLeft, new LinearLayout.LayoutParams(dp(40), dp(40)));

        tvTitle = new TextView(this);
        tvTitle.setText("MUSIC");
        tvTitle.setTextColor(GREEN);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.MONOSPACE);
        tvTitle.setSingleLine(true);
        tvTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(12);
        topBar.addView(tvTitle, titleLp);

        btnSearch = new ImageView(this);
        btnSearch.setImageResource(R.drawable.ic_search_black_24);
        btnSearch.setColorFilter(GREEN);
        btnSearch.setOnClickListener(v -> toggleSearch());
        topBar.addView(btnSearch, new LinearLayout.LayoutParams(dp(40), dp(40)));

        btnSort = new ImageView(this);
        btnSort.setImageResource(R.drawable.ic_sort);
        btnSort.setColorFilter(GREEN);
        btnSort.setOnClickListener(this::showSortMenu);
        topBar.addView(btnSort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setBackgroundColor(BG);
        searchRow.setPadding(dp(12), dp(4), dp(12), dp(8));
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setVisibility(View.GONE);

        searchInput = new EditText(this);
        searchInput.setHint("search…");
        searchInput.setHintTextColor(DIM);
        searchInput.setTextColor(GREEN);
        searchInput.setBackgroundColor(BG);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setTypeface(Typeface.MONOSPACE);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.US);
                refreshList();
            }
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        recycler = new RecyclerView(this);
        recycler.setBackgroundColor(BG);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        adapter = new TrackAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyLabel = new TextView(this);
        emptyLabel.setTextColor(DIM);
        emptyLabel.setTextSize(13);
        emptyLabel.setTypeface(Typeface.MONOSPACE);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyLp.topMargin = dp(24);
        root.addView(emptyLabel, emptyLp);

        btnAdd = new Button(this);
        btnAdd.setText("ADD");
        btnAdd.setTextColor(GREEN);
        btnAdd.setBackgroundColor(BG);
        btnAdd.setTypeface(Typeface.MONOSPACE);
        btnAdd.setTextSize(14);
        btnAdd.setAllCaps(false);
        btnAdd.setVisibility(View.GONE);
        btnAdd.setOnClickListener(v -> enterPicker());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = dp(12);
        addLp.rightMargin = dp(12);
        addLp.bottomMargin = dp(6);
        root.addView(btnAdd, addLp);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(BG);
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottomBar.setVisibility(View.GONE);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        btnPrev = new ImageView(this);
        btnPrev.setImageResource(R.drawable.ic_skip_back);
        btnPrev.setColorFilter(GREEN);
        btnPrev.setOnClickListener(v -> playPrevious());
        navRow.addView(btnPrev, new LinearLayout.LayoutParams(dp(36), dp(36)));

        btnPlayPause = new ImageView(this);
        btnPlayPause.setImageResource(R.drawable.ic_pause);
        btnPlayPause.setColorFilter(GREEN);
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        LinearLayout.LayoutParams ppLp =
                new LinearLayout.LayoutParams(dp(36), dp(36));
        ppLp.leftMargin = dp(4);
        navRow.addView(btnPlayPause, ppLp);

        tvNowPlaying = new TextView(this);
        tvNowPlaying.setTextColor(GREEN);
        tvNowPlaying.setTextSize(12);
        tvNowPlaying.setTypeface(Typeface.MONOSPACE);
        tvNowPlaying.setSingleLine(true);
        tvNowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvNowPlaying.setMarqueeRepeatLimit(-1);
        tvNowPlaying.setSelected(true);
        LinearLayout.LayoutParams npLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        npLp.leftMargin  = dp(8);
        npLp.rightMargin = dp(8);
        navRow.addView(tvNowPlaying, npLp);

        btnNext = new ImageView(this);
        btnNext.setImageResource(R.drawable.ic_skip_forward);
        btnNext.setColorFilter(GREEN);
        btnNext.setOnClickListener(v -> playNext());
        navRow.addView(btnNext, new LinearLayout.LayoutParams(dp(36), dp(36)));

        bottomBar.addView(navRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF003300));
        seekBar.setPadding(0, dp(6), 0, dp(6));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    try { player.seekTo(p); } catch (Exception ignored) { }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    private void maybeRequestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (isIgnoringBatteryOptimizations()) return;
        blackDialog()
                .setTitle("Allow background playback")
                .setMessage("Android may stop music when the screen is off.\n\n" +
                        "Tap \"Allow\" and choose \"Unrestricted\" for this app " +
                        "so playback keeps running in the background.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        Intent i = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(i, REQ_BATTERY_OPT);
                    } catch (Exception e) {
                        try {
                            Intent fallback = new Intent(
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(fallback);
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void loadPlaylist() {
        try {
            android.content.SharedPreferences p =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String s = p.getString(KEY_PLAYLIST, "");
            filterActive = p.getBoolean(KEY_FILTER_ACTIVE, false);
            activePlaylistIds.clear();
            if (s != null && s.length() > 0) {
                for (String part : s.split(",")) {
                    try { activePlaylistIds.add(Long.parseLong(part)); }
                    catch (NumberFormatException ignored) { }
                }
            }
            if (activePlaylistIds.isEmpty()) filterActive = false;
        } catch (Exception ignored) { }
    }

    private void savePlaylist() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Long id : activePlaylistIds) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PLAYLIST, sb.toString())
                    .putBoolean(KEY_FILTER_ACTIVE, filterActive)
                    .commit();
        } catch (Exception ignored) { }
    }

    private void saveLastViewState() {
        try {
            int v = (viewState == VIEW_PLAYLIST) ? VIEW_PLAYLIST : VIEW_LIBRARY;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LAST_VIEW_STATE, v)
                    .apply();
        } catch (Exception ignored) { }
    }

    private int loadLastViewState() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(KEY_LAST_VIEW_STATE, VIEW_LIBRARY);
        } catch (Exception e) {
            return VIEW_LIBRARY;
        }
    }

    private void savePlaybackState(boolean isPlaylist, List<Track> list) {
        try {
            StringBuilder sb = new StringBuilder();
            if (list != null) {
                for (Track t : list) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(t.id);
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PLAYBACK_IS_PLAYLIST, isPlaylist)
                    .putString(KEY_PLAYBACK_LIST_KIND,
                            isPlaylist ? KIND_PLAYLIST : KIND_LIBRARY)
                    .putString(KEY_PLAYBACK_LIST_IDS, sb.toString())
                    .commit();
        } catch (Exception ignored) { }
    }

    private boolean loadPlaybackIsPlaylist() {
        try {
            android.content.SharedPreferences p =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            String kind = p.getString(KEY_PLAYBACK_LIST_KIND, null);
            if (kind != null) return KIND_PLAYLIST.equals(kind);
            return p.getBoolean(KEY_PLAYBACK_IS_PLAYLIST, false);
        } catch (Exception e) {
            return false;
        }
    }

    private List<Long> loadPlaybackListIds() {
        List<Long> ids = new ArrayList<>();
        try {
            String s = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_PLAYBACK_LIST_IDS, "");
            if (s != null && !s.isEmpty()) {
                for (String part : s.split(",")) {
                    try { ids.add(Long.parseLong(part)); }
                    catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return ids;
    }

    private void requestAudioPermission() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.READ_MEDIA_AUDIO");
            perms.add("android.permission.POST_NOTIFICATIONS");
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add("android.permission.BLUETOOTH_CONNECT");
        }
        ActivityCompat.requestPermissions(this,
                perms.toArray(new String[0]), REQ_AUDIO);
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this,
                    "android.permission.READ_MEDIA_AUDIO")
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadTracks();
        } else {
            blackDialog()
                    .setTitle("Permission required")
                    .setMessage("Music Player needs access to audio files.\n\n" +
                            "Open settings?")
                    .setPositiveButton("Settings", (d, w) -> {
                        Intent i = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BATTERY_OPT) {
            if (isIgnoringBatteryOptimizations()) {
                Toast.makeText(this, "Background playback enabled",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Still restricted — music may stop when the screen is off",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadTracks() {
        io.execute(() -> {
            List<Track> found = new ArrayList<>();
            ContentResolver cr = getContentResolver();
            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.SIZE
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0"
                    + " OR " + MediaStore.Audio.Media.IS_PODCAST + "!=0";

            try (Cursor c = cr.query(collection, projection, selection, null, null)) {
                if (c != null) {
                    int iId    = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int iArt   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int iData  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int iDur   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int iAdd   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    int iMod   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
                    int iSize  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);

                    while (c.moveToNext()) {
                        Track t = new Track();
                        t.id = c.getLong(iId);
                        t.title = c.getString(iTitle);
                        if (t.title == null || t.title.isEmpty()) t.title = "Unknown";
                        t.artist = c.getString(iArt);
                        if (t.artist == null) t.artist = "";
                        t.path = c.getString(iData);
                        t.durationMs = c.getLong(iDur);
                        t.dateAdded = c.getLong(iAdd);
                        t.dateModified = c.getLong(iMod);
                        t.size = c.getLong(iSize);
                        t.contentUri = ContentUris.withAppendedId(collection, t.id);
                        found.add(t);
                    }
                }
            } catch (Exception e) {
                FileLog.e("MusicPlayer: MediaStore query failed", e);
            }

            ui.post(() -> {
                allTracks.clear();
                allTracks.addAll(found);
                if (allTracks.isEmpty()) {
                    Toast.makeText(this, "No audio files found",
                            Toast.LENGTH_SHORT).show();
                }
                refreshList();
                updateToolbar();

                if (pendingStartLast) {
                    pendingStartLast = false;
                    List<Track> walk = resolvePlaybackList();
                    playFromList(walk, lastPlayedId);
                }
                if (pendingCmdFromLaunch != null) {
                    String c = pendingCmdFromLaunch;
                    pendingCmdFromLaunch = null;
                    handleControlCommand(c);
                }
                maybeAutoPlayIncoming();
            });
        });
    }

    private void refreshList() {
        List<Track> base;
        if (viewState == VIEW_PLAYLIST) {
            base = new ArrayList<>();
            if (filterActive && !activePlaylistIds.isEmpty()) {
                for (Long id : activePlaylistIds) {
                    for (Track t : allTracks) {
                        if (t.id == id) { base.add(t); break; }
                    }
                }
            }
        } else {
            base = new ArrayList<>(allTracks);
        }

        if (!searchQuery.isEmpty()) {
            List<Track> filtered = new ArrayList<>();
            for (Track t : base) {
                if (t.title.toLowerCase(Locale.US).contains(searchQuery)
                        || t.artist.toLowerCase(Locale.US).contains(searchQuery)) {
                    filtered.add(t);
                }
            }
            base = filtered;
        }

        Collections.sort(base, buildLibraryComparator());

        visibleTracks.clear();
        visibleTracks.addAll(base);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private Comparator<Track> buildLibraryComparator() {
        switch (sortMode) {
            case SORT_LATEST:
                return (a, b) -> {
                    int c1 = Long.compare(b.dateModified, a.dateModified);
                    if (c1 != 0) return c1;
                    int c2 = Long.compare(b.size, a.size);
                    if (c2 != 0) return c2;
                    return a.title.compareToIgnoreCase(b.title);
                };
            case SORT_OLDEST:
                return (a, b) -> {
                    int c1 = Long.compare(a.dateModified, b.dateModified);
                    if (c1 != 0) return c1;
                    int c2 = Long.compare(a.size, b.size);
                    if (c2 != 0) return c2;
                    return a.title.compareToIgnoreCase(b.title);
                };
            case SORT_ZA:
                return (a, b) -> b.title.compareToIgnoreCase(a.title);
            case SORT_AZ:
            default:
                return (a, b) -> a.title.compareToIgnoreCase(b.title);
        }
    }

    private void updateToolbar() {
        switch (viewState) {
            case VIEW_PLAYLIST:
                btnLeft.setImageResource(R.drawable.ic_back);
                tvTitle.setText("PLAYLIST");
                break;
            case VIEW_PICKER:
                btnLeft.setImageResource(R.drawable.ic_check);
                tvTitle.setText("SELECT TRACKS");
                break;
            case VIEW_LIBRARY:
            default:
                btnLeft.setImageResource(R.drawable.ic_menu);
                tvTitle.setText("MUSIC");
                break;
        }
    }

    private void updateEmptyState() {
        if (emptyLabel == null || btnAdd == null) return;
        btnAdd.setVisibility(viewState == VIEW_PLAYLIST ? View.VISIBLE : View.GONE);
        if (visibleTracks.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            if (!searchQuery.isEmpty()) {
                emptyLabel.setText("No matches.");
            } else if (viewState == VIEW_PLAYLIST) {
                emptyLabel.setText("No playlist.\nTap ADD to choose tracks.");
            } else {
                emptyLabel.setText("No audio files on this device.");
            }
        } else {
            emptyLabel.setVisibility(View.GONE);
        }
    }

    private void onLeftIconTapped() {
        switch (viewState) {
            case VIEW_LIBRARY:
                viewState = VIEW_PLAYLIST;
                break;
            case VIEW_PLAYLIST:
                viewState = VIEW_LIBRARY;
                break;
            case VIEW_PICKER:
                activePlaylistIds.clear();
                activePlaylistIds.addAll(pickBuffer);
                pickBuffer.clear();
                filterActive = !activePlaylistIds.isEmpty();
                viewState = VIEW_PLAYLIST;
                savePlaylist();
                break;
        }
        refreshList();
        updateToolbar();
        saveLastViewState();
    }

    private void enterPicker() {
        pickBuffer.clear();
        for (Long id : activePlaylistIds) pickBuffer.add(id);
        viewState = VIEW_PICKER;
        refreshList();
        updateToolbar();
    }

    private void toggleSearch() {
        if (searchOpen) closeSearch();
        else openSearch();
    }

    private void openSearch() {
        searchOpen = true;
        searchRow.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        btnSearch.setColorFilter(NOW_PLAY);
    }

    private void closeSearch() {
        searchOpen = false;
        searchQuery = "";
        searchInput.setText("");
        searchRow.setVisibility(View.GONE);
        btnSearch.setColorFilter(GREEN);
        refreshList();
    }

    private void showSortMenu(View anchor) {
        ContextThemeWrapper w = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(w, anchor);
        popup.getMenu().add(0, 1, 0, "Latest first");
        popup.getMenu().add(0, 2, 1, "Oldest first");
        popup.getMenu().add(0, 3, 2, "A \u2192 Z");
        popup.getMenu().add(0, 4, 3, "Z \u2192 A");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: sortMode = SORT_LATEST; break;
                case 2: sortMode = SORT_OLDEST; break;
                case 3: sortMode = SORT_AZ;     break;
                case 4: sortMode = SORT_ZA;     break;
            }
            refreshList();
            return true;
        });
        forceBlackPopupBackground(popup);
        popup.show();
    }

    private void togglePlayPause() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                isPaused = true;
                isPausedStatic = true;
                btnPlayPause.setImageResource(R.drawable.ic_play_circle);
                btnPlayPause.setColorFilter(NOW_PLAY);
                ui.removeCallbacks(progressTick);
            } else {
                player.start();
                isPaused = false;
                isPausedStatic = false;
                btnPlayPause.setImageResource(R.drawable.ic_pause);
                btnPlayPause.setColorFilter(GREEN);
                ui.post(progressTick);
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                    new Intent("vaddempudi.gnanesh.syntaxcli.MUSIC_STATE_CHANGED"));
        } catch (Exception ignored) { }
    }

    private List<Track> buildSourceListForCommand() {
        List<Track> base = new ArrayList<>();
        if (filterActive && !activePlaylistIds.isEmpty()) {
            for (Long id : activePlaylistIds) {
                for (Track t : allTracks) {
                    if (t.id == id) { base.add(t); break; }
                }
            }
            if (base.isEmpty()) {
                base.addAll(allTracks);
                Collections.sort(base, buildLibraryComparator());
            }
        } else {
            base.addAll(allTracks);
            Collections.sort(base, buildLibraryComparator());
        }
        return base;
    }

    private List<Track> resolvePlaybackList() {
        boolean isPlaylist = loadPlaybackIsPlaylist();
        List<Long> ids = loadPlaybackListIds();

        if (ids.isEmpty()) {
            return buildSourceListForCommand();
        }

        List<Track> rebuilt = new ArrayList<>();
        for (Long id : ids) {
            for (Track t : allTracks) {
                if (t.id == id) { rebuilt.add(t); break; }
            }
        }

        if (rebuilt.isEmpty()) {
            return buildSourceListForCommand();
        }

        if (isPlaylist) {
            Collections.sort(rebuilt, buildLibraryComparator());
        } else {
            Set<Long> known = new HashSet<>();
            for (Track t : rebuilt) known.add(t.id);
            boolean added = false;
            for (Track t : allTracks) {
                if (!known.contains(t.id)) { rebuilt.add(t); added = true; }
            }
            if (added) {
                Collections.sort(rebuilt, buildLibraryComparator());
                savePlaybackState(false, rebuilt);
            }
        }

        currentPlaybackList = rebuilt;
        currentPlaybackIsPlaylist = isPlaylist;
        return rebuilt;
    }

    private boolean sameList(List<Track> a, List<Track> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).id != b.get(i).id) return false;
        }
        return true;
    }

    private void playTrack(int index) {
        if (index < 0 || index >= visibleTracks.size()) return;
        int savedState = viewState;
        if (viewState == VIEW_PICKER) {
            viewState = VIEW_LIBRARY;
        }
        playTrackByObject(visibleTracks.get(index), visibleTracks);
        viewState = savedState;
    }

    private void playTrackByObject(Track t, List<Track> sourceList) {
        if (t == null) return;

        boolean isPlaylist = (viewState == VIEW_PLAYLIST);
        savePlaybackState(isPlaylist, sourceList);

        currentPlaybackList = sourceList != null
                ? new ArrayList<>(sourceList) : null;
        currentPlaybackIsPlaylist = isPlaylist;

        if (alive && sourceList != null) {
            if (!sameList(sourceList, visibleTracks)) {
                visibleTracks.clear();
                visibleTracks.addAll(sourceList);
                int newIndex = -1;
                for (int i = 0; i < visibleTracks.size(); i++) {
                    if (visibleTracks.get(i).id == t.id) { newIndex = i; break; }
                }
                playingIndex = newIndex >= 0 ? newIndex : 0;
                if (adapter != null) adapter.notifyDataSetChanged();
            } else {
                int newIndex = -1;
                for (int i = 0; i < visibleTracks.size(); i++) {
                    if (visibleTracks.get(i).id == t.id) { newIndex = i; break; }
                }
                if (newIndex >= 0) playingIndex = newIndex;
            }
        } else {
            int newIndex = 0;
            if (sourceList != null) {
                for (int i = 0; i < sourceList.size(); i++) {
                    if (sourceList.get(i).id == t.id) { newIndex = i; break; }
                }
            }
            playingIndex = newIndex;
        }

        lastPlayedId = t.id;
        lastPlayedTitle = t.title;
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent("vaddempudi.gnanesh.syntaxcli.MUSIC_TRACK_CHANGED"));

        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, t.contentUri);
            player.setOnPreparedListener(mp -> {
                mp.start();
                isPaused = false;
                isPausedStatic = false;
                if (btnPlayPause != null) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause);
                    btnPlayPause.setColorFilter(GREEN);
                }
                if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
                if (tvNowPlaying != null) {
                    tvNowPlaying.setText("\u25B6  " + t.title
                            + (t.artist.isEmpty() ? "" : "  \u2013 " + t.artist));
                }
                if (seekBar != null) {
                    seekBar.setMax(mp.getDuration());
                    seekBar.setProgress(0);
                }
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
            player.setOnCompletionListener(mp -> playNext());
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show();
                return true;
            });
            player.prepareAsync();

            try {
                Intent svc = new Intent(this, MusicPlaybackService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } catch (Exception ignored) { }

            ui.postDelayed(() -> {
                try {
                    Intent upd = new Intent(
                            MusicPlaybackService.ACTION_UPDATE_NOTIFICATION);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(upd);
                } catch (Exception ignored) { }
            }, 250);
        } catch (Exception e) {
            FileLog.e("MusicPlayer: play failed", e);
            Toast.makeText(this, "Cannot play: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void playFromList(List<Track> list, long preferredId) {
        if (list == null || list.isEmpty()) return;
        int idx = -1;
        if (preferredId >= 0) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == preferredId) { idx = i; break; }
            }
        }
        if (idx < 0) idx = 0;
        playTrackByObject(list.get(idx), list);
    }

    private void playNextFromList(List<Track> list) {
        if (list == null || list.isEmpty()) return;
        int cur = -1;
        if (lastPlayedId >= 0) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == lastPlayedId) { cur = i; break; }
            }
        }
        if (cur < 0) cur = 0;
        int next = (cur + 1) % list.size();
        playTrackByObject(list.get(next), list);
    }

    private void playPrevFromList(List<Track> list) {
        if (list == null || list.isEmpty()) return;
        int cur = -1;
        if (lastPlayedId >= 0) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == lastPlayedId) { cur = i; break; }
            }
        }
        if (cur < 0) cur = 0;
        int prev = (cur - 1 + list.size()) % list.size();
        playTrackByObject(list.get(prev), list);
    }

    private void playNext() {
        playNextFromList(resolvePlaybackList());
    }

    private void playPrevious() {
        playPrevFromList(resolvePlaybackList());
    }

    private void releasePlayer() {
        ui.removeCallbacks(progressTick);
        isPaused = false;
        isPausedStatic = true;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        // Do not stop MusicPlaybackService here. This method is also called
        // during every track change, so stopping the service here destroys
        // its MediaSession and removes the notification between songs.
        // Keep the foreground service alive while switching tracks.
    }

    private void handleControlCommand(String cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case CMD_PLAY:
                if (player != null && !player.isPlaying() && isPaused) {
                    togglePlayPause();
                } else if (player == null) {
                    playFromList(resolvePlaybackList(), lastPlayedId);
                }
                break;
            case CMD_PAUSE:
                if (player != null && player.isPlaying()) togglePlayPause();
                break;
            case CMD_TOGGLE:
                if (player == null) {
                    playFromList(resolvePlaybackList(), lastPlayedId);
                } else {
                    togglePlayPause();
                }
                break;
            case CMD_NEXT:
                playNextFromList(resolvePlaybackList());
                break;
            case CMD_PREV:
                playPrevFromList(resolvePlaybackList());
                break;
            case CMD_OPEN_PLAYLIST:
                viewState = VIEW_PLAYLIST;
                refreshList();
                updateToolbar();
                saveLastViewState();
                break;
            case CMD_START_LAST:
                playFromList(resolvePlaybackList(), lastPlayedId);
                break;
        }
    }

    public static long getLastPlayedId() { return lastPlayedId; }
    public static String getLastPlayedTitle() { return lastPlayedTitle; }
    public static boolean isCurrentlyPlaying() { return !isPausedStatic; }

    private class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MusicPlayerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(BG);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(dp(12), dp(6), 0, dp(6));

            ImageView check = new ImageView(MusicPlayerActivity.this);
            check.setColorFilter(GREEN);
            check.setVisibility(View.GONE);
            row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(28)));

            LinearLayout textCol = new LinearLayout(MusicPlayerActivity.this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.leftMargin = dp(10);
            row.addView(textCol, textLp);

            TextView title = new TextView(MusicPlayerActivity.this);
            title.setTextColor(GREEN);
            title.setTextSize(14);
            title.setTypeface(Typeface.MONOSPACE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView sub = new TextView(MusicPlayerActivity.this);
            sub.setTextColor(DIM);
            sub.setTextSize(11);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(sub);

            ImageView dots = new ImageView(MusicPlayerActivity.this);
            dots.setImageResource(R.drawable.ic_more_vert);
            dots.setColorFilter(GREEN);
            dots.setPadding(dp(6), dp(6), dp(6), dp(6));
            int dotsWidth = dp(40);
            LinearLayout.LayoutParams dotsLp =
                    new LinearLayout.LayoutParams(dotsWidth, dotsWidth);
            row.addView(dots, dotsLp);

            return new VH(row, check, title, sub, dots);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Track t = visibleTracks.get(position);
            boolean pickerMode = (viewState == VIEW_PICKER);

            h.title.setText(t.title);
            StringBuilder sb = new StringBuilder();
            if (!t.artist.isEmpty()) sb.append(t.artist).append("  \u2022  ");
            sb.append(fmt(t.durationMs));
            h.sub.setText(sb.toString());

            if (pickerMode) {
                h.check.setVisibility(View.VISIBLE);
                h.check.setImageResource(pickBuffer.contains(t.id)
                        ? R.drawable.ic_checkbox_checked
                        : R.drawable.ic_checkbox_empty);
            } else {
                h.check.setVisibility(View.GONE);
            }

            boolean isPlaying = playingIndex >= 0
                    && playingIndex < visibleTracks.size()
                    && visibleTracks.get(playingIndex) == t;

            if (isPlaying) {
                GradientDrawable border = new GradientDrawable();
                border.setColor(BG);
                border.setStroke(dp(2), GREEN);
                h.itemView.setBackground(border);
                h.title.setTextColor(NOW_PLAY);
                h.sub.setTextColor(NOW_PLAY);
            } else {
                h.itemView.setBackgroundColor(BG);
                h.title.setTextColor(GREEN);
                h.sub.setTextColor(DIM);
            }

            h.dots.setVisibility(pickerMode ? View.GONE : View.VISIBLE);

            final int pos = h.getAdapterPosition();

            h.itemView.setOnClickListener(v -> {
                if (viewState == VIEW_PICKER) {
                    if (pickBuffer.contains(t.id)) pickBuffer.remove(t.id);
                    else pickBuffer.add(t.id);
                    notifyItemChanged(pos);
                } else {
                    playTrack(pos);
                }
            });

            h.dots.setOnClickListener(v -> {
                if (viewState != VIEW_PICKER) showTrackActions(v, t);
            });
        }

        @Override
        public int getItemCount() { return visibleTracks.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView check, dots;
            final TextView title, sub;
            VH(@NonNull View v, ImageView c, TextView t, TextView s, ImageView d) {
                super(v);
                check = c; title = t; sub = s; dots = d;
            }
        }
    }

    private void showTrackActions(View anchor, Track track) {
        ContextThemeWrapper w = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(w, anchor);
        popup.getMenu().add(0, 1, 0, "Delete");
        popup.getMenu().add(0, 2, 1, "Add to playlist");
        popup.getMenu().add(0, 3, 2, "Rename");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: confirmDelete(track); return true;
                case 2: addToPlaylist(track); return true;
                case 3: showRenameDialog(track); return true;
            }
            return false;
        });
        forceBlackPopupBackground(popup);
        popup.show();
    }

    private void confirmDelete(Track track) {
        blackDialog()
                .setTitle("Delete")
                .setMessage("Delete \"" + track.title + "\" from device?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        int rows = getContentResolver().delete(
                                track.contentUri, null, null);
                        if (rows > 0) {
                            Toast.makeText(this, "Deleted",
                                    Toast.LENGTH_SHORT).show();
                            allTracks.remove(track);
                            visibleTracks.remove(track);
                            activePlaylistIds.remove((Long) track.id);
                            pickBuffer.remove((Long) track.id);
                            if (activePlaylistIds.isEmpty()) filterActive = false;
                            savePlaylist();
                            releasePlayer();
                            playingIndex = -1;
                            bottomBar.setVisibility(View.GONE);
                            refreshList();
                        } else {
                            Toast.makeText(this, "Delete failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        FileLog.e("MusicPlayer: delete failed", e);
                        Toast.makeText(this, "Delete failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addToPlaylist(Track track) {
        if (!activePlaylistIds.contains(track.id)) {
            activePlaylistIds.add(track.id);
        }
        filterActive = true;
        savePlaylist();
        Toast.makeText(this, "Added to playlist (" + activePlaylistIds.size() + ")",
                Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(Track track) {
        EditText input = new EditText(this);
        input.setText(track.title);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(GREEN);
        input.setHintTextColor(DIM);
        input.setBackgroundColor(BG);
        blackDialog()
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String nn = input.getText().toString().trim();
                    if (nn.isEmpty()) return;
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Audio.Media.TITLE, nn);
                        int rows = getContentResolver().update(
                                track.contentUri, cv, null, null);
                        if (rows > 0) {
                            track.title = nn;
                            try {
                                if (track.path != null) {
                                    File src = new File(track.path);
                                    if (src.exists() && src.canWrite()) {
                                        String ext = "";
                                        int dot = src.getName().lastIndexOf('.');
                                        if (dot > 0)
                                            ext = src.getName().substring(dot);
                                        File dst = new File(
                                                src.getParentFile(), nn + ext);
                                        src.renameTo(dst);
                                    }
                                }
                            } catch (Exception ignored) { }
                            refreshList();
                        } else {
                            Toast.makeText(this, "Rename failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        FileLog.e("MusicPlayer: rename failed", e);
                        Toast.makeText(this, "Rename failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_EDIT.equals(action)) {
            return;
        }
        Uri data = intent.getData();
        if (data == null && Intent.ACTION_SEND.equals(action)) {
            data = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (data == null) return;
        String path = resolveIncomingPath(data);
        if (path == null) {
            Toast.makeText(this, "Cannot open this audio file",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        launchedForIncomingFile = true;
        pendingIncomingPath = path;
        maybeAutoPlayIncoming();
    }

    private String resolveIncomingPath(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) return uri.getPath();
        if ("content".equalsIgnoreCase(scheme)) {
            Cursor c = null;
            try {
                c = getContentResolver().query(uri,
                        new String[]{MediaStore.MediaColumns.DATA},
                        null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if (idx >= 0) {
                        String p = c.getString(idx);
                        if (p != null && new File(p).exists()) return p;
                    }
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
            try {
                String name = null;
                Cursor c2 = null;
                try {
                    c2 = getContentResolver().query(uri,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                            null, null, null);
                    if (c2 != null && c2.moveToFirst()) {
                        int idx = c2.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        if (idx >= 0) name = c2.getString(idx);
                    }
                } finally { if (c2 != null) c2.close(); }
                if (name == null) name = "incoming_" + System.currentTimeMillis() + ".mp3";
                File cacheDir = new File(getCacheDir(), "incoming_audio");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File out = new File(cacheDir, name);
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    if (in == null) return null;
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                return out.getAbsolutePath();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void maybeAutoPlayIncoming() {
        if (pendingIncomingPath == null) return;
        if (visibleTracks == null) return;
        if (allTracks.isEmpty()) return;
        String target = pendingIncomingPath;
        pendingIncomingPath = null;
        for (int i = 0; i < visibleTracks.size(); i++) {
            Track t = visibleTracks.get(i);
            if (t.path != null && t.path.equals(target)) {
                playTrack(i);
                return;
            }
        }
        File f = new File(target);
        if (!f.exists()) return;
        Track t = new Track();
        t.id = -1;
        t.title = f.getName();
        t.artist = "";
        t.path = f.getAbsolutePath();
        t.size = f.length();
        t.dateModified = f.lastModified() / 1000;
        t.dateAdded = t.dateModified;
        t.durationMs = 0;
        t.contentUri = Uri.fromFile(f);
        visibleTracks.add(0, t);
        allTracks.add(0, t);
        adapter.notifyDataSetChanged();
        playTrack(0);
    }

    private AlertDialog.Builder blackDialog() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.BlackDialog));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        long m = s / 60;
        long r = s % 60;
        return String.format(Locale.US, "%d:%02d", m, r);
    }

    private void forceBlackPopupBackground(PopupMenu popup) {
        try {
            java.lang.reflect.Field[] fields = popup.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                if (!"mPopup".equals(f.getName())) continue;
                f.setAccessible(true);
                Object helper = f.get(popup);
                try {
                    java.lang.reflect.Method m = helper.getClass()
                            .getMethod("setForceShowIcon", boolean.class);
                    m.invoke(helper, true);
                } catch (Exception ignored) { }
                try {
                    java.lang.reflect.Field pf = helper.getClass()
                            .getDeclaredField("mPopup");
                    pf.setAccessible(true);
                    Object lp = pf.get(helper);
                    java.lang.reflect.Method bg = lp.getClass()
                            .getMethod("setBackgroundDrawable",
                                    android.graphics.drawable.Drawable.class);
                    bg.invoke(lp, ContextCompat.getDrawable(this, R.drawable.popup_bg));
                } catch (Exception ignored) { }
                break;
            }
        } catch (Exception ignored) { }
    }
}