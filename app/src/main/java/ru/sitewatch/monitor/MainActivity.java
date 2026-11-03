package ru.sitewatch.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;
    private static final int[] INTERVALS = {1, 5, 15, 30, 60, 180};
    private static final String[] INTERVAL_LABELS = {
            "1 минута", "5 минут", "15 минут", "30 минут", "1 час", "3 часа"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Set<String> checking = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private String renderedSignature = null;

    private LinearLayout listContainer;
    private TextView summary;
    private Button checkNowButton;
    private Switch settingsNotifySwitch;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderSites();
            checkDueSites();
            handler.postDelayed(this, 4000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listContainer = findViewById(R.id.siteList);
        summary = findViewById(R.id.summary);
        checkNowButton = findViewById(R.id.checkNowButton);
        TextView addButton = findViewById(R.id.addButton);
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        RecoveryNotifier.prepare(this);
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        checkNowButton.setOnClickListener(v -> {
            List<SiteStore.Site> sites = SiteStore.all(this);
            if (sites.isEmpty()) {
                Toast.makeText(this, "Сначала добавьте сайт", Toast.LENGTH_SHORT).show();
                return;
            }
            for (SiteStore.Site site : sites) startCheck(site);
        });
        addButton.setOnClickListener(v -> showAddDialog());

        SiteStore.applyHttpPolicy(this);
        CheckWorker.schedule(this);
        renderSites();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        executor.shutdown();
        super.onDestroy();
    }

    private void showSettingsDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Интервал проверки");
        intervalLabel.setTextColor(Color.parseColor("#1D2C48"));
        intervalLabel.setTypeface(null, Typeface.BOLD);
        intervalLabel.setTextSize(16);
        container.addView(intervalLabel);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, INTERVAL_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        spinnerParams.topMargin = dp(4);
        container.addView(spinner, spinnerParams);

        TextView intervalHint = new TextView(this);
        intervalHint.setTextColor(Color.parseColor("#72809A"));
        intervalHint.setTextSize(12);
        container.addView(intervalHint);
        updateIntervalHint(intervalHint);

        int selected = 1;
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == SiteStore.getInterval(this)) selected = i;
        }
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                int interval = INTERVALS[position];
                if (interval != SiteStore.getInterval(MainActivity.this)) {
                    SiteStore.setInterval(MainActivity.this, interval);
                    CheckWorker.schedule(MainActivity.this);
                    checkDueSites();
                }
                updateIntervalHint(intervalHint);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Switch toggle = new Switch(this);
        toggle.setText("Уведомлять о восстановлении");
        toggle.setTextColor(Color.parseColor("#1D2C48"));
        toggle.setTextSize(14);
        toggle.setChecked(SiteStore.notificationsEnabled(this));
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.topMargin = dp(22);
        toggleParams.bottomMargin = dp(8);
        container.addView(toggle, toggleParams);
        settingsNotifySwitch = toggle;
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
                return;
            }
            SiteStore.setNotificationsEnabled(this, isChecked);
        });

        TextView note = new TextView(this);
        note.setText("Уведомление приходит, когда сайт был недоступен, " +
                "а затем снова начал отвечать.");
        note.setTextColor(Color.parseColor("#72809A"));
        note.setTextSize(12);
        container.addView(note);

        Switch httpToggle = new Switch(this);
        httpToggle.setText("Разрешить незащищенный HTTP");
        httpToggle.setTextColor(Color.parseColor("#1D2C48"));
        httpToggle.setChecked(SiteStore.httpEnabled(this));
        LinearLayout.LayoutParams httpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        httpParams.topMargin = dp(20);
        container.addView(httpToggle, httpParams);
        httpToggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled == SiteStore.httpEnabled(this)) return;
            if (!enabled) {
                SiteStore.setHttpEnabled(this, false);
                renderSites();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Разрешить HTTP?")
                    .setMessage("Все HTTP-адреса из списка будут проверяться без шифрования. " +
                            "Участники сети могут видеть путь и параметры запроса и подменять ответ. " +
                            "Не используйте адреса с паролями или токенами. " +
                            "Переход с HTTPS на HTTP останется запрещен.")
                    .setPositiveButton("Разрешить", (dialog, which) -> {
                        SiteStore.setHttpEnabled(this, true);
                        for (SiteStore.Site site : SiteStore.all(this)) startCheck(site);
                    })
                    .setNegativeButton("Отмена", (dialog, which) -> httpToggle.setChecked(false))
                    .setOnCancelListener(dialog -> httpToggle.setChecked(false))
                    .show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(container)
                .setPositiveButton("Готово", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (settingsNotifySwitch == toggle) settingsNotifySwitch = null;
        });
        dialog.show();
    }

    private void updateIntervalHint(TextView hint) {
        int minutes = SiteStore.getInterval(this);
        if (minutes < 15) {
            hint.setText("Пока приложение открыто — каждые " + minutes +
                    " мин. В фоне — ориентировочно раз в 15 мин или реже.");
        } else {
            hint.setText("Проверки выполняются и в фоне, но Android может задерживать запуск.");
        }
    }

    private void checkDueSites() {
        int minutes = SiteStore.getInterval(this);
        long now = System.currentTimeMillis();
        long age = minutes * 60_000L;
        for (SiteStore.Site site : SiteStore.all(this)) {
            if (site.checkedAt == 0 || now - site.checkedAt >= age) startCheck(site);
        }
    }

    private void startCheck(SiteStore.Site site) {
        if (!checking.add(site.id)) return;
        renderSites();
        try {
            executor.execute(() -> {
                try {
                    SiteChecker.checkOne(getApplicationContext(), site);
                } finally {
                    checking.remove(site.id);
                    handler.post(this::renderSites);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            checking.remove(site.id);
        }
    }

    private void showAddDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com");
        input.setTextSize(16);
        int horizontalPadding = dp(22);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(horizontalPadding, dp(8), horizontalPadding, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView errorMessage = new TextView(this);
        errorMessage.setTextColor(Color.parseColor("#B3261E"));
        errorMessage.setTextSize(14);
        errorMessage.setPadding(0, dp(6), 0, dp(4));
        errorMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorMessage.setVisibility(View.GONE);
        box.addView(errorMessage);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Добавить сайт")
                .setMessage("Укажите адрес сайта. Можно просто example.com")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        String url = UrlPolicy.normalize(input.getText().toString());
                        SiteStore.Site added = SiteStore.add(this, url);
                        if (added == null) {
                            errorMessage.setText("Этот сайт уже добавлен");
                            errorMessage.setVisibility(View.VISIBLE);
                            return;
                        }
                        dialog.dismiss();
                        CheckWorker.schedule(this);
                        renderSites();
                        startCheck(added);
                    } catch (IllegalArgumentException e) {
                        errorMessage.setText(e.getMessage());
                        errorMessage.setVisibility(View.VISIBLE);
                    }
                }));
        dialog.show();
    }

    private void confirmDelete(SiteStore.Site site) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить сайт?")
                .setMessage(site.url + " больше не будет проверяться.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    SiteStore.remove(this, site.id);
                    CheckWorker.schedule(this);
                    renderSites();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void renderSites() {
        List<SiteStore.Site> sites = SiteStore.all(this);
        StringBuilder signature = new StringBuilder();
        for (SiteStore.Site site : sites) {
            signature.append(site.id).append('|').append(site.state).append('|')
                    .append(site.details).append('|').append(site.errorType).append('|')
                    .append(site.checkedAt)
                    .append('|').append(checking.contains(site.id)).append(';');
        }
        String newSignature = signature.toString();
        if (newSignature.equals(renderedSignature)) return;
        renderedSignature = newSignature;

        listContainer.removeAllViews();
        int up = 0;
        int down = 0;
        int blocked = 0;
        for (SiteStore.Site site : sites) {
            if ("UP".equals(site.state)) up++;
            if ("DOWN".equals(site.state)) down++;
            if ("BLOCKED".equals(site.state)) blocked++;
            listContainer.addView(buildCard(site));
        }
        if (sites.isEmpty()) {
            summary.setText("Добавьте первый сайт, нажав +");
            TextView empty = new TextView(this);
            empty.setText("Здесь появятся ваши сайты.\n\n" +
                    "✓ Зелёный — доступен\n× Красный — недоступен\n\n" +
                    "Нажмите +, чтобы добавить адрес.");
            empty.setTextColor(Color.parseColor("#6B7991"));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setBackgroundResource(R.drawable.bg_card);
            empty.setPadding(dp(24), dp(40), dp(24), dp(40));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            listContainer.addView(empty, params);
        } else {
            summary.setText("Всего: " + sites.size() + "   ✓ Доступно: " + up +
                    "   × Недоступно: " + down +
                    (blocked > 0 ? "   ! Заблокировано: " + blocked : ""));
        }
        checkNowButton.setText(checking.isEmpty() ? "Проверить сейчас" :
                "Проверяем… (" + checking.size() + ")");
    }

    private View buildCard(SiteStore.Site site) {
        boolean working = checking.contains(site.id);
        boolean blocked = "BLOCKED".equals(site.state);
        int statusColor = working || blocked ? Color.parseColor("#BE7B23") :
                "UP".equals(site.state) ? Color.parseColor("#079B7F") :
                "DOWN".equals(site.state) ? Color.parseColor("#E14D58") :
                        Color.parseColor("#7584A0");
        int statusBg = working || blocked ? Color.parseColor("#FFF3DB") :
                "UP".equals(site.state) ? Color.parseColor("#DFF8EF") :
                "DOWN".equals(site.state) ? Color.parseColor("#FFEBED") :
                        Color.parseColor("#EDF1F7");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(16), dp(15), dp(16));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(11);
        card.setLayoutParams(cardParams);

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText(working ? "…" : "UP".equals(site.state) ? "✓" :
                "DOWN".equals(site.state) ? "×" : blocked ? "!" : "?");
        icon.setTextSize(24);
        icon.setTypeface(null, Typeface.BOLD);
        icon.setTextColor(statusColor);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundBg(statusBg, 12, 0, 0));
        firstRow.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameParams.leftMargin = dp(12);
        firstRow.addView(names, nameParams);

        TextView hostname = new TextView(this);
        try {
            hostname.setText(new URL(site.url).getHost());
        } catch (Exception e) {
            hostname.setText(site.url);
        }
        hostname.setTextColor(Color.parseColor("#1B2B47"));
        hostname.setTextSize(16);
        hostname.setTypeface(null, Typeface.BOLD);
        hostname.setSingleLine(true);
        hostname.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(hostname);

        TextView subtitle = new TextView(this);
        subtitle.setText(site.url);
        subtitle.setTextColor(Color.parseColor("#75839A"));
        subtitle.setTextSize(12);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(subtitle);

        TextView delete = new TextView(this);
        delete.setText("×");
        delete.setTextSize(27);
        delete.setTextColor(Color.parseColor("#9AABC0"));
        delete.setGravity(Gravity.CENTER);
        delete.setContentDescription("Удалить " + site.url);
        firstRow.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(44)));
        delete.setOnClickListener(v -> confirmDelete(site));
        card.addView(firstRow);

        TextView status = new TextView(this);
        String label = working ? "Проверяем сайт…" :
                blocked ? "Проверка заблокирована (POLICY)" :
                "UP".equals(site.state) ? "Сайт доступен" :
                        "DOWN".equals(site.state) ? "Сайт недоступен" +
                                (site.httpCode > 0 ? " (" + site.httpCode + ")" :
                                        site.errorType.isEmpty() ? "" :
                                                " (" + site.errorType + ")") :
                                "Ожидает проверки";
        status.setText(label);
        status.setTextColor(statusColor);
        status.setTypeface(null, Typeface.BOLD);
        status.setTextSize(14);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(13);
        card.addView(status, statusParams);

        TextView details = new TextView(this);
        String text = site.details;
        if (site.checkedAt > 0) {
            String time = new SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault())
                    .format(new Date(site.checkedAt));
            text += "  •  " + site.latencyMs + " мс  •  " + time;
        }
        details.setText(text);
        details.setTextSize(12);
        details.setTextColor(Color.parseColor("#7C8799"));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        card.addView(details, detailParams);

        card.setOnClickListener(v -> startCheck(site));
        return card;
    }

    private GradientDrawable roundBg(int fillColor, float cornerDp,
                                     int strokeColor, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(cornerDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;
            SiteStore.setNotificationsEnabled(this, granted);
            if (settingsNotifySwitch != null) settingsNotifySwitch.setChecked(granted);
            if (!granted) Toast.makeText(this,
                    "Уведомления отключены: нет разрешения", Toast.LENGTH_LONG).show();
        }
    }
}
