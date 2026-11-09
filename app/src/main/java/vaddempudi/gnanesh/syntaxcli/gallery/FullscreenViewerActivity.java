package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import android.graphics.Color;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import vaddempudi.gnanesh.syntaxcli.R;
import android.view.GestureDetector;
import android.view.MotionEvent;


public class FullscreenViewerActivity extends AppCompatActivity {

    private static final String LOG_TAG = "gallery-tui";

    private final String idHash = Integer.toHexString(System.identityHashCode(this));

    private String src() {
        return "[FullscreenViewer:" + idHash + "]";
    }

    private void log(String msg) {
        Log.d(LOG_TAG, src() + " " + msg);
    }

    private ViewPager2 viewPager;
    private FullscreenAdapter adapter;
    private List<String> mediaPaths = new ArrayList<>();
    private int currentPosition = 0;

    private boolean suppressChromeUntilNextTap = false;

    /** The controller for the currently visible video page, or null. */
    private HardwareVideoController currentVideoView = null;

    private View videoControlContainer;
    private ImageButton btnCenterPlayPause, btnSkipForward, btnSkipBackward;
    private ImageButton btnFavorite, btnInfo, btnDelete, btnRotate;
    private TextView videoTimeCurrent, videoTimeTotal, videoTitle;
    private SeekBar videoSeekBar;

    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable updateProgressRunnable;
    private Runnable hideControlsRunnable;

    private boolean controlsVisible = true;
    private static final int CONTROLS_TIMEOUT = 3000;
    private static final int SKIP_FORWARD_MS = 15000;
    private static final int SKIP_BACKWARD_MS = 5000;

    private boolean isVideoPlaying = false;
    private boolean isLandscape = false;

    private View decorView;
    private int currentSystemUiVisibility;
    private FrameLayout rootLayout;
    private boolean currentPageIsVideo = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        for (int _i = 0; _i < 50; _i++) {
            Log.d("gallery-tui", "[FullscreenViewer] ★★★ MARKER-FULLSCREEN-VIEWER-ACTIVITY-COMPILED-MARKER-"
                    + _i + " hash=" + idHash + " ★★★");
        }
        super.onCreate(savedInstanceState);
        log("========================================================");
        log("onCreate — activity hash=" + idHash);
        log("========================================================");
        setContentView(R.layout.activity_fullscreen_viewer);

        ViewPager2 vpPre = findViewById(R.id.fullscreenViewPager);
        if (vpPre != null) {
            killPagerScrollBars(vpPre);
        }
        decorView = getWindow().getDecorView();
        setImmersiveFullscreen();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        setupSystemUiVisibilityListener();

        viewPager = findViewById(R.id.fullscreenViewPager);
        videoControlContainer = findViewById(R.id.videoControlContainer);
        btnCenterPlayPause = findViewById(R.id.btnCenterPlayPause);
        btnSkipForward = findViewById(R.id.btnSkipForward);
        btnSkipBackward = findViewById(R.id.btnSkipBackward);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnInfo = findViewById(R.id.btnInfo);
        btnDelete = findViewById(R.id.btnDelete);
        btnRotate = findViewById(R.id.btnRotate);
        videoTimeCurrent = findViewById(R.id.videoTimeCurrent);
        videoTimeTotal = findViewById(R.id.videoTimeTotal);
        videoTitle = findViewById(R.id.videoTitle);
        videoSeekBar = findViewById(R.id.videoSeekBar);
        rootLayout = findViewById(R.id.videoControlsOverlay);
        if (rootLayout != null) {
            rootLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        log("views bound: videoControlContainer=" + (videoControlContainer != null)
                + " btnCenterPlayPause=" + (btnCenterPlayPause != null)
                + " viewPager=" + (viewPager != null));

        mediaPaths = getIntent().getStringArrayListExtra("media_paths");
        currentPosition = getIntent().getIntExtra("current_position", 0);

        if (mediaPaths == null || mediaPaths.isEmpty()) {
            log("no mediaPaths, finishing");
            finish();
            return;
        }

        log("mediaPaths size=" + mediaPaths.size() + " currentPosition=" + currentPosition);

        adapter = new FullscreenAdapter(mediaPaths, this);
        log("adapter created: hash=" + Integer.toHexString(System.identityHashCode(adapter)));

        adapter.setTapCallback(() -> {
            log("tap callback fired");
            toggleControlsVisibility();
        });

        final FullscreenAdapter.PageTypeCallback callback = new FullscreenAdapter.PageTypeCallback() {
            @Override
            public void onImageVisible(int position) {
                Log.d(LOG_TAG, src() + " ★★ onImageVisible ENTER pos=" + position
                        + " currentPosition=" + currentPosition);
                if (position != currentPosition) {
                    Log.d(LOG_TAG, src() + "   ignoring (not current)");
                    return;
                }

                currentPageIsVideo = false;
                stopCurrentVideo();

                if (videoControlContainer != null) {
                    videoControlContainer.setVisibility(View.GONE);
                }
                Log.d(LOG_TAG, src() + " ★★ onImageVisible EXIT");
            }

            @Override
            public void onVideoVisible(HardwareVideoController ctrl, int position) {
                Log.d(LOG_TAG, src() + " ★★ onVideoVisible ENTER pos=" + position
                        + " currentPosition=" + currentPosition
                        + " ctrl=" + Integer.toHexString(System.identityHashCode(ctrl)));

                if (position != currentPosition) {
                    Log.d(LOG_TAG, src() + "   ignoring (not current)");
                    return;
                }

                currentPageIsVideo = true;
                currentVideoView = ctrl;

                if (videoControlContainer != null) {
                    videoControlContainer.setVisibility(View.VISIBLE);
                    Log.d(LOG_TAG, src() + "   videoControlContainer → VISIBLE");
                }

                try {
                    Log.d(LOG_TAG, src() + "   calling ctrl.start()");
                    ctrl.start();
                    isVideoPlaying = true;
                    Log.d(LOG_TAG, src() + "   isPlaying=" + ctrl.isPlaying()
                            + " duration=" + ctrl.getDuration());
                    if (btnCenterPlayPause != null) {
                        btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                    }
                    startProgressUpdate();
                    showVideoControlsWithTimeout();
                } catch (Exception e) {
                    Log.e(LOG_TAG, src() + " ctrl.start() threw", e);
                    isVideoPlaying = false;
                    if (btnCenterPlayPause != null) {
                        btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    }
                }
                Log.d(LOG_TAG, src() + " ★★ onVideoVisible EXIT");
            }
        };

        Log.d(LOG_TAG, src() + " created PageTypeCallback: hash="
                + Integer.toHexString(System.identityHashCode(callback)));

        adapter.setPageTypeCallback(callback);

        Log.d(LOG_TAG, src() + " verification: adapter=" + Integer.toHexString(System.identityHashCode(adapter))
                + " callback=" + Integer.toHexString(System.identityHashCode(callback)));

        viewPager.setAdapter(adapter);
        killPagerScrollBars(viewPager);
        viewPager.setCurrentItem(currentPosition, false);
        viewPager.setOffscreenPageLimit(1);

        setupVideoControls();
        setupTapToToggleChrome();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                suppressChromeUntilNextTap = false;
                Log.d(LOG_TAG, src() + " onPageSelected: " + position + " (was " + currentPosition + ")");

                if (position != currentPosition) {
                    stopCurrentVideo();
                }

                currentPosition = position;
                currentVideoView = null;
                isVideoPlaying = false;
                currentPageIsVideo = false;

                if (videoControlContainer != null) {
                    videoControlContainer.setVisibility(View.GONE);
                }

                updateTitle();
                resetVideoUi();
            }
        });

        updateTitle();
    }

    private void setupTapToToggleChrome() {
        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }

                    @Override public boolean onDoubleTap(MotionEvent e) {
                        cancelPendingChromeTap();
                        if (!currentPageIsVideo || currentVideoView == null) return false;
                        suppressChromeUntilNextTap = true;
                        float w = getWindow().getDecorView().getWidth();
                        if (w <= 0) return false;
                        if (e.getX() < w / 3f) skipByMs(-SKIP_BACKWARD_MS);
                        else if (e.getX() > w * 2f / 3f) skipByMs(SKIP_FORWARD_MS);
                        else togglePlayPause();
                        return true;
                    }

                    @Override public boolean onDoubleTapEvent(MotionEvent e) {
                        cancelPendingChromeTap();
                        return true;
                    }
                });

        View decor = getWindow().getDecorView();
        decor.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return false;
        });
    }


    private void skipByMs(int deltaMs) {
        if (currentVideoView == null) return;
        long durL = currentVideoView.getSafeDuration();
        if (durL <= 0) return;
        int dur = (int) durL;
        long curL = currentVideoView.getSafePosition();
        int cur = (int) Math.max(0L, curL);
        int target = Math.max(0, Math.min(cur + deltaMs, dur));
        currentVideoView.seekToSafe(target);
        updateSeekBar();
        if (!suppressChromeUntilNextTap) {
            showVideoControlsWithTimeout();
        }
    }

    private Runnable pendingChromeTapRunnable = null;

    private void cancelPendingChromeTap() {
        if (pendingChromeTapRunnable != null) {
            videoHandler.removeCallbacks(pendingChromeTapRunnable);
            pendingChromeTapRunnable = null;
        }
    }

    private void scheduleSingleTapOrCancel() {
        cancelPendingChromeTap();
        pendingChromeTapRunnable = () -> {
            pendingChromeTapRunnable = null;
            suppressChromeUntilNextTap = false;
            if (controlsVisible) hideControls();
            else showControls();
        };
        videoHandler.postDelayed(pendingChromeTapRunnable, 260L);
    }

    private void toggleControlsVisibility() {
        if (controlsVisible) hideControls();
        else showControls();
    }

    private void showControls() {
        if (!isActivityAlive()) return;
        setChromeVisible(true);

        videoHandler.removeCallbacks(hideControlsRunnable);
        if (currentPageIsVideo && isVideoPlaying) {
            hideControlsRunnable = this::hideControls;
            videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
        }
    }

    private void hideControls() {
        setChromeVisible(false);
        videoHandler.removeCallbacks(hideControlsRunnable);
    }

    private void showVideoControlsWithTimeout() {
        if (suppressChromeUntilNextTap) return;
        setChromeVisible(true);

        videoHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = this::hideControls;
        videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void killPagerScrollBars(ViewPager2 vp) {
        if (vp == null) return;
        vp.setVerticalScrollBarEnabled(false);
        vp.setHorizontalScrollBarEnabled(false);
        vp.post(() -> {
            try {
                RecyclerView rv = (RecyclerView) vp.getChildAt(0);
                if (rv != null) {
                    rv.setVerticalScrollBarEnabled(false);
                    rv.setHorizontalScrollBarEnabled(false);
                    rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                    rv.setScrollbarFadingEnabled(false);
                    rv.setVerticalFadingEdgeEnabled(false);
                    rv.setHorizontalFadingEdgeEnabled(false);
                }
            } catch (Exception ignored) {}
        });
    }


    private void setChromeVisible(boolean visible) {
        controlsVisible = visible;

        if (videoControlContainer != null) {
            videoControlContainer.setVisibility(
                    visible && currentPageIsVideo ? View.VISIBLE : View.GONE);
        }
        setImmersiveFullscreen();
    }



    private void resetVideoUi() {
        if (btnCenterPlayPause != null) {
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
        if (videoTimeCurrent != null) videoTimeCurrent.setText("00:00");
        if (videoTimeTotal != null) videoTimeTotal.setText("00:00");
        if (videoSeekBar != null) videoSeekBar.setProgress(0);
    }

    private void setImmersiveFullscreen() {
        if (decorView == null) return;

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }

        decorView.setSystemUiVisibility(flags);
        currentSystemUiVisibility = flags;
    }

    private void setupSystemUiVisibilityListener() {
        if (decorView == null) return;
        decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                videoHandler.postDelayed(this::setImmersiveFullscreen, 100);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersiveFullscreen();
    }

    private void stopCurrentVideo() {
        log("stopCurrentVideo: currentVideoView=" + currentVideoView);
        if (currentVideoView != null) {
            try {
                currentVideoView.stopPlayback();
            } catch (Exception ignored) {}
            currentVideoView = null;
        }
        videoHandler.removeCallbacks(updateProgressRunnable);
        isVideoPlaying = false;
    }

    private void setupVideoControls() {
        if (btnCenterPlayPause != null) {
            btnCenterPlayPause.setOnClickListener(v -> togglePlayPause());
        }

        if (btnSkipForward != null) {
            btnSkipForward.setOnClickListener(v -> {
                if (currentVideoView != null) {
                    long durL = currentVideoView.getSafeDuration();
                    int dur = durL > 0 ? (int) durL : 0;
                    long curL = currentVideoView.getSafePosition();
                    int cur = (int) Math.max(0L, curL);
                    currentVideoView.seekToSafe(Math.min(cur + SKIP_FORWARD_MS, dur));
                    updateSeekBar();
                    showVideoControlsWithTimeout();
                }
            });
        }

        if (btnSkipBackward != null) {
            btnSkipBackward.setOnClickListener(v -> {
                if (currentVideoView != null) {
                    long curL = currentVideoView.getSafePosition();
                    int cur = (int) Math.max(0L, curL);
                    currentVideoView.seekToSafe(Math.max(cur - SKIP_BACKWARD_MS, 0));
                    updateSeekBar();
                    showVideoControlsWithTimeout();
                }
            });
        }

        if (btnFavorite != null) {
            btnFavorite.setOnClickListener(v -> toggleFavorite(mediaPaths.get(currentPosition)));
        }
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showFileInfoDialog(mediaPaths.get(currentPosition)));
        }
        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> moveToTrash(mediaPaths.get(currentPosition)));
        }
        if (btnRotate != null) {
            btnRotate.setOnClickListener(v -> toggleOrientation());
        }

        if (videoSeekBar != null) {
            videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && currentVideoView != null) {
                        long durL = currentVideoView.getSafeDuration();
                        int dur = durL > 0 ? (int) durL : 0;
                        int newPosition = (int) ((progress / 100.0) * dur);
                        currentVideoView.seekToSafe(newPosition);
                        showVideoControlsWithTimeout();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { showVideoControlsWithTimeout(); }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { showVideoControlsWithTimeout(); }
            });
        }
    }

    private void togglePlayPause() {
        log("togglePlayPause: currentVideoView=" + currentVideoView
                + " isVideoPlaying=" + isVideoPlaying);
        if (!isActivityAlive() || currentVideoView == null) return;

        if (isVideoPlaying) {
            currentVideoView.pause();
            if (btnCenterPlayPause != null) {
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
            isVideoPlaying = false;
            videoHandler.removeCallbacks(updateProgressRunnable);
            showVideoControlsWithTimeout();
        } else {
            currentVideoView.start();
            if (btnCenterPlayPause != null) {
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            }
            isVideoPlaying = true;
            startProgressUpdate();
            showVideoControlsWithTimeout();
        }
    }

    private void startProgressUpdate() {
        videoHandler.removeCallbacks(updateProgressRunnable);
        updateProgressRunnable = new Runnable() {
            @Override
            public void run() {
                updateSeekBar();
                if (isVideoPlaying) videoHandler.postDelayed(this, 500);
            }
        };
        videoHandler.post(updateProgressRunnable);
    }

    private void updateSeekBar() {
        if (currentVideoView == null) return;
        try {
            long curL = currentVideoView.getSafePosition();
            long durL = currentVideoView.getSafeDuration();
            if (durL > 0) {
                int current = (int) Math.max(0L, curL);
                int duration = (int) durL;
                if (videoTimeCurrent != null) videoTimeCurrent.setText(formatTime(current));
                if (videoTimeTotal != null) videoTimeTotal.setText(formatTime(duration));
                if (videoSeekBar != null) {
                    videoSeekBar.setProgress((int) ((current / (float) duration) * 100));
                }
            }
        } catch (Exception ignored) {}
    }

    private String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
    private void toggleFavorite(String path) {
        if (btnFavorite == null) return;
        btnFavorite.setColorFilter(Color.parseColor("#FFD700"));
        if (btnFavorite.getColorFilter() == null) {
            btnFavorite.setColorFilter(Color.parseColor("#FFD700"));
        } else {
            btnFavorite.clearColorFilter();
        }
    }

    private void showFileInfoDialog(String path) {
        File file = new File(path);
        if (!file.exists()) return;

        StringBuilder info = new StringBuilder();
        info.append("📄 File: ").append(file.getName()).append("\n");
        info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("📅 Modified: ").append(new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",
                Locale.getDefault()).format(new java.util.Date(file.lastModified()))).append("\n");
        info.append("🔤 Type: ").append(getFileType(path)).append("\n");
        info.append("📍 Path: ").append(file.getAbsolutePath());

        showInfoDialog(info.toString(), "📄 File Info");
    }

    private void showInfoDialog(String info, String title) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_info, null);
        TextView infoTitle = view.findViewById(R.id.infoTitle);
        TextView infoContent = view.findViewById(R.id.infoContent);
        Button infoClose = view.findViewById(R.id.infoClose);

        if (infoTitle != null) infoTitle.setText(title);
        if (infoContent != null) infoContent.setText(info);

        builder.setView(view);
        android.app.AlertDialog dialog = builder.create();
        if (infoClose != null) infoClose.setOnClickListener(v -> dialog.dismiss());

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void moveToTrash(String path) {
        File file = new File(path);
        if (!file.exists()) return;
        String parent = file.getParent();
        String name = file.getName();
        String cleanName = name.replaceAll("^\\.trashed\\.", "");
        File trashedFile = new File(parent, ".trashed." + cleanName);

        if (file.renameTo(trashedFile)) {
            stopCurrentVideo();
            mediaPaths.remove(currentPosition);
            adapter.notifyDataSetChanged();
            if (mediaPaths.isEmpty()) {
                finish();
            } else if (currentPosition >= mediaPaths.size()) {
                currentPosition = mediaPaths.size() - 1;
                viewPager.setCurrentItem(currentPosition, false);
            }
        }
    }

    private void toggleOrientation() {
        if (isLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isLandscape = false;
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            isLandscape = true;
        }
        videoHandler.postDelayed(this::setImmersiveFullscreen, 300);
    }

    private String getFileType(String path) {
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase(Locale.US);
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|heif")) return "Image";
        if (ext.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v")) return "Video";
        return "Unknown";
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private void updateTitle() {
        if (currentPosition < mediaPaths.size()) {
            File file = new File(mediaPaths.get(currentPosition));
            if (videoTitle != null) videoTitle.setText(file.getName());
        }
    }

    @Override
    public void onBackPressed() {
        log("onBackPressed");
        if (isLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isLandscape = false;
            videoHandler.postDelayed(() -> {
                stopCurrentVideo();
                if (decorView != null) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
                super.onBackPressed();
                finish();
            }, 300);
        } else {
            stopCurrentVideo();
            if (decorView != null) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            super.onBackPressed();
            finish();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        log("onResume");
        setImmersiveFullscreen();
        if (currentVideoView != null && !currentVideoView.isPlaying() && isVideoPlaying) {
            currentVideoView.start();
            startProgressUpdate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("onDestroy");
        stopCurrentVideo();
        videoHandler.removeCallbacks(updateProgressRunnable);
        videoHandler.removeCallbacks(hideControlsRunnable);
        if (decorView != null) {
            decorView.setOnSystemUiVisibilityChangeListener(null);
        }
    }

    boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    public int getCurrentPosition() {
        return currentPosition;
    }

    public void setCurrentVideoView(HardwareVideoController videoView) {
        this.currentVideoView = videoView;
    }
}