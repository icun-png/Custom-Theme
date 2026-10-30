 package com.gathin.tvhome;

  import android.animation.Animator;
  import android.animation.AnimatorListenerAdapter;
  import android.animation.ObjectAnimator;
  import android.content.Context;
  import android.content.res.Resources;
  import android.graphics.Bitmap;
  import android.graphics.BitmapFactory;
  import android.graphics.Canvas;
  import android.graphics.Color;
  import android.graphics.Paint;
  import android.graphics.Rect;
  import android.graphics.drawable.BitmapDrawable;
  import android.graphics.drawable.ColorDrawable;
  import android.graphics.drawable.Drawable;
  import android.graphics.drawable.LayerDrawable;
  import android.os.Handler;
  import android.os.Looper;
  import android.util.DisplayMetrics;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;

  import java.io.File;
  import java.time.LocalDateTime;
  import java.time.ZoneId;
  import java.time.format.DateTimeFormatter;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Map;
  import java.util.Random;
  import java.util.Set;

  import androidx.core.graphics.ColorUtils;

  /**
   * Windows 11 Style Lockscreen:
   * - Static centered clock (no moving)
   * - Separate lockscreen wallpaper collection
   * - Frosted glass panel behind clock with blur
   * - Thin modern font (Outfit), 24h format
   * - Date below clock, subtle glow
   */
  final class ClockScene {

      private static final long UPDATE_MS = 1000L;   // update clock every second
      private static final int PANEL_PADDING_H = 48; // dp
      private static final int PANEL_PADDING_V = 32; // dp
      private static final int PANEL_RADIUS = 28;    // dp

      private final Handler handler = new Handler(Looper.getMainLooper());
      private final Random random = new Random();
      private final View root;
      private final ImageView background;
      private final View panel;
      private final TextView clockView;
      private final TextView dateView;
      private final TextView dayView;
      private final Paint panelPaint;
      private final Rect panelRect = new Rect();
      private int screenW, screenH;
      private boolean wallpaperLoaded;
      private int panelBlurAlpha;
      private int panelBgColor;
      private File wallpaperFile;
      private List<File> rotation;
      private long rotationMs;
      private String order;
      private Map<String, Set<String>> moods;
      private String latitude, longitude;
      private int wallpaperShade;
      private Resources wallpaperRes;
      private int wallpaperGeneration;
      private DateTimeFormatter timeFormat;
      private DateTimeFormatter dateFormat;
      private DateTimeFormatter dayFormat;
      private boolean is24h;
      private boolean win11Mode;
      private boolean centered;

      private final Runnable tick = new Runnable() {
          @Override public void run() {
              updateDateTime();
              advanceWallpaper();
              handler.postDelayed(this, UPDATE_MS);
          }
      };

      /** root = inflated dream_clock layout */
      ClockScene(View root) {
          this.root = root;
          background = root.findViewById(R.id.dreamAmbient);
          panel = root.findViewById(R.id.dreamPanel);
          clockView = root.findViewById(R.id.dreamClock);
          dateView = root.findViewById(R.id.dreamDate);
          dayView = root.findViewById(R.id.dreamDay);

          panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      }

      void start() {
          root.setAlpha(0f);
          root.post(() -> {
              measurePanel();
              root.animate().alpha(1f).setDuration(800).start();
          });
          handler.removeCallbacks(tick);
          handler.post(tick);
          if (wallpaperFile != null && !wallpaperLoaded) loadWallpaper();
      }

      void stop() {
          handler.removeCallbacksAndMessages(null);
          root.animate().cancel();
          wallpaperGeneration++;
      }

      /**
       * Configure from Config: separate lockscreen wallpaper, frosted panel, centered clock.
       * Call before start().
       */
      void useHomeWallpaper(Context ctx) {
          Config cfg;
          try { cfg = Config.load(ctx); }
          catch (RuntimeException e) { return; }

          win11Mode = cfg.lockscreenWin11Enabled;
          centered = cfg.lockscreenClockCentered;

          // Time/date formatters
          is24h = "24".equals(cfg.clockFormat) || ("system".equals(cfg.clockFormat) &&
  android.text.format.DateFormat.is24HourFormat(ctx));
          timeFormat = is24h ? DateTimeFormatter.ofPattern("HH:mm") : DateTimeFormatter.ofPattern("h:mm a");
          dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy");
          dayFormat = DateTimeFormatter.ofPattern("EEEE");

          // Apply font (Outfit already loaded via UiFont)
          if (clockView != null) UiFont.apply(clockView, UiFont.WEIGHT_THIN, 120);
          if (dateView != null) UiFont.apply(dateView, UiFont.WEIGHT_LIGHT, 28);
          if (dayView != null) UiFont.apply(dayView, UiFont.WEIGHT_LIGHT, 24);

          // Panel styling
          panelBlurAlpha = cfg.lockscreenPanelBlur;
          panelBgColor = (cfg.lockscreenPanelOpacity << 24) | 0x000000;

          // --- LOCKSCREEN WALLPAPER (separate collection) ---
          if (cfg.lockscreenWallpaperEnabled && !cfg.lockscreenWallpapers.isEmpty()) {
              rotation = new ArrayList<>(cfg.lockscreenWallpapers);
              String shown = ctx.getSharedPreferences("lockscreen_wallpaper_rotation", Context.MODE_PRIVATE)
                      .getString("image", "");
              File current = WallpaperRotation.find(rotation, shown);
              wallpaperFile = current != null ? current : rotation.get(0);
              rotationMs = cfg.lockscreenWallpaperRotationMinutes * 60_000L;
              order = cfg.lockscreenWallpaperOrder;
              moods = cfg.lockscreenWallpaperMoods;
              latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
              longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
              wallpaperShade = (cfg.lockscreenWallpaperDim * 255 / 100) << 24;
              wallpaperRes = ctx.getResources();
              return;
          }

          // --- FALLBACK: Homescreen wallpaper (original behavior) ---
          if (!cfg.clockWallpaper) {
              if (!cfg.clockGlow) {
                  wallpaperLoaded = true;
                  background.setBackground(null);
                  if (panel != null) panel.setBackground(null);
              }
              return;
          }
          if (cfg.wallpapers.isEmpty()) {
              wallpaperFile = null;
          } else {
              rotation = new ArrayList<>(cfg.wallpapers);
              String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                      .getString("image", "");
              File current = WallpaperRotation.find(rotation, shown);
              wallpaperFile = current != null ? current : rotation.get(0);
              rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
              order = cfg.wallpaperOrder;
              moods = cfg.wallpaperMoods;
              latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
              longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
          }
          wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
          wallpaperRes = ctx.getResources();
      }

      private void loadWallpaper() {
          File file = wallpaperFile;
          int shade = wallpaperShade;
          Resources res = wallpaperRes;
          DisplayMetrics dm = res.getDisplayMetrics();
          int w = dm.widthPixels, h = dm.heightPixels;
          int generation = ++wallpaperGeneration;
          new Thread(() -> {
              Bitmap bmp = decode(file, w, h);
              if (bmp == null) return;
              handler.post(() -> {
                  if (generation != wallpaperGeneration || wallpaperLoaded) {
                      bmp.recycle();
                      return;
                  }
                  wallpaperLoaded = true;
                  background.setBackground(null);
                  background.setImageDrawable(new LayerDrawable(new Drawable[]{
                          new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
              });
          }, "lockscreen-wallpaper").start();
      }

      private static Bitmap decode(File file, int screenW, int screenH) {
          try {
              BitmapFactory.Options options = new BitmapFactory.Options();
              options.inJustDecodeBounds = true;
              BitmapFactory.decodeFile(file.getPath(), options);
              int width = options.outWidth, height = options.outHeight;
              if (width <= 0 || height <= 0) return null;
              int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
              if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
              options.inJustDecodeBounds = false;
              options.inSampleSize = sample;
              int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
              if (scale != null) {
                  options.inScaled = true;
                  options.inDensity = scale[0];
                  options.inTargetDensity = scale[1];
              }
              options.inPreferredConfig = Bitmap.Config.RGB_565;
              return BitmapFactory.decodeFile(file.getPath(), options);
          } catch (Exception | OutOfMemoryError e) {
              return null;
          }
      }

      private void advanceWallpaper() {
          if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
          // Check every minute (60 seconds)
          if (System.currentTimeMillis() % rotationMs != 0) return;
          File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
          if (next.equals(wallpaperFile)) return;
          wallpaperFile = next;
          wallpaperLoaded = false;
          loadWallpaper();
      }

      private List<File> playable() {
          if (moods == null || moods.isEmpty()) return rotation;
          LocalDateTime now = LocalDateTime.now();
          int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
          return WallpaperMood.select(rotation, moods,
                  WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
      }

      private void updateDateTime() {
          LocalDateTime now = LocalDateTime.now();
          String timeStr = now.format(timeFormat);
          String dateStr = now.format(dateFormat);
          String dayStr = now.format(dayFormat);

          if (clockView != null) clockView.setText(timeStr);
          if (dateView != null) dateView.setText(dateStr);
          if (dayView != null) dayView.setText(dayStr);
      }

      private void measurePanel() {
          if (panel == null) return;
          DisplayMetrics dm = root.getResources().getDisplayMetrics();
          screenW = dm.widthPixels;
          screenH = dm.heightPixels;

          int padH = (int) (PANEL_PADDING_H * dm.density);
          int padV = (int) (PANEL_PADDING_V * dm.density);
          int radius = (int) (PANEL_RADIUS * dm.density);

          // Panel wraps clock + date + day
          int panelW = Math.min(screenW - padH * 2, clockView.getMeasuredWidth() + padH * 2);
          int panelH = clockView.getMeasuredHeight() + dateView.getMeasuredHeight() + dayView.getMeasuredHeight() + padV
  * 2 + 24;

          int left = (screenW - panelW) / 2;
          int top = (screenH - panelH) / 2;
          int right = left + panelW;
          int bottom = top + panelH;

          panelRect.set(left, top, right, bottom);
          panel.setLayoutParams(new ViewGroup.LayoutParams(panelW, panelH));
          panel.setX(left);
          panel.setY(top);
          panel.setBackground(createFrostedBackground(radius));
      }

      private Drawable createFrostedBackground(int radius) {
          return new Drawable() {
              @Override public void draw(Canvas canvas) {
                  // Background dim
                  canvas.drawColor(wallpaperShade != 0 ? wallpaperShade : 0x99000000);
                  // Frosted panel
                  Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                  p.setColor(panelBgColor);
                  canvas.drawRoundRect(panelRect.left, panelRect.top, panelRect.right, panelRect.bottom, radius, radius,
  p);
                  // Subtle border highlight (top edge)
                  p.setColor(0x33FFFFFF);
                  p.setStyle(Paint.Style.STROKE);
                  p.setStrokeWidth(1.5f);
                  canvas.drawRoundRect(panelRect.left + 0.75f, panelRect.top + 0.75f,
                          panelRect.right - 0.75f, panelRect.bottom - 0.75f, radius, radius, p);
              }
              @Override public void setAlpha(int alpha) {}
              @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
              @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
          };
      }
  }

  ---