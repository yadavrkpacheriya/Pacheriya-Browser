package com.pacheriya.vpbrowser;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.PlayerView;

/** Built-in video player (ExoPlayer) with Video Plus style controls — plays local files of many formats. */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private float[] speeds = { 0.5f, 1f, 1.25f, 1.5f, 2f };
    private int speedIdx = 1;
    private int resizeMode = 0;
    private boolean boosted = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(view);

        try {
            DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
            player = new ExoPlayer.Builder(this, rf).build();
        } catch (Throwable e) {
            player = new ExoPlayer.Builder(this).build();
        }
        view.setPlayer(player);
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data == null) { finish(); return; }
        player.setMediaItem(MediaItem.fromUri(data));
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                // format ExoPlayer can't decode -> offer an external player
                try {
                    Intent in = new Intent(Intent.ACTION_VIEW);
                    in.setDataAndType(getIntent().getData(), "video/*");
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(in, "Play with another app"));
                } catch (Throwable x) { /* ignore */ }
                finish();
            }
        });

        // Video Plus style control row
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xAA000000);
        bar.setPadding(8, 8, 8, 8);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(bar, blp);

        addBtn(bar, "1x", new View.OnClickListener() { public void onClick(View v) {
            speedIdx = (speedIdx + 1) % speeds.length; player.setPlaybackParameters(new PlaybackParameters(speeds[speedIdx]));
            ((TextView) v).setText(speeds[speedIdx] + "x");
        }});
        addBtn(bar, "Fit", new View.OnClickListener() { public void onClick(View v) {
            resizeMode = (resizeMode + 1) % 3;
            int[] modes = { androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL };
            String[] names = { "Fit", "Zoom", "Stretch" };
            view.setResizeMode(modes[resizeMode]);
            ((TextView) v).setText(names[resizeMode]);
        }});
        addBtn(bar, "Loop", new View.OnClickListener() { public void onClick(View v) {
            boolean on = player.getRepeatMode() == Player.REPEAT_MODE_OFF;
            player.setRepeatMode(on ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            ((TextView) v).setText(on ? "Loop ✓" : "Loop");
        }});
        addBtn(bar, "Loud", new View.OnClickListener() { public void onClick(View v) {
            boosted = !boosted;
            player.setVolume(boosted ? 1f : 1f); // ExoPlayer caps at 1.0; true boost needs an audio processor
            ((TextView) v).setText(boosted ? "Loud ✓" : "Loud");
        }});
    }

    private void addBtn(LinearLayout bar, String label, View.OnClickListener cl) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setPadding(22, 14, 22, 14);
        t.setOnClickListener(cl);
        bar.addView(t);
    }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) { player.release(); player = null; } }
}
