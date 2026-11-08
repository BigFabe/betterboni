package app.betterboni;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.CheckBox;
import android.widget.ToggleButton;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private int BG, INK, MUTED, GREEN, LINE, RED, GOLD;
    private boolean darkTheme;
    private String themeMode;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Restaurant> restaurants = new ArrayList<>(), visible = new ArrayList<>();
    private final RestaurantAdapter adapter = new RestaurantAdapter();
    private LinearLayout root, home;
    private ScrollView settingsPage;
    private TextView status, empty, locationStatus;
    private Location position;
    private LocationManager locationManager;
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { updatePosition(location); }
        @Override public void onProviderEnabled(String provider) { startLocation(); }
        @Override public void onProviderDisabled(String provider) { startLocation(); }
    };
    private EditText search;
    private ImageButton searchButton, filterButton;
    private Switch menuLanguageToggle;
    private ProgressBar startupSpinner;
    private boolean searchOpen;
    private final Set<String> selectedServices = new HashSet<>();
    private final Map<String, String> serviceOptions = new LinkedHashMap<>();
    private int minPrice = 0, maxPrice = Integer.MAX_VALUE, minRating = 0;
    private double radiusMeters = Double.POSITIVE_INFINITY;
    private Set<String> favorites;
    private boolean onlyFavorites, showClosed, showWithoutMenu, hoursLoading;
    private final Map<String, Boolean> menuAvailability = new LinkedHashMap<>();
    private HoursRepository hoursRepository;
    private final Map<String, String> hoursSchedules = new LinkedHashMap<>();
    private final Map<String, OpeningHours.Status> availability = new LinkedHashMap<>();
    private final Handler hoursClock = new Handler(Looper.getMainLooper());
    private final Runnable renderRestaurants = this::filter;
    private final Runnable hoursTick = new Runnable() {
        @Override public void run() {
            hoursSchedules.forEach((id, schedule) -> availability.put(id, OpeningHours.status(schedule)));
            filter();
            hoursClock.postDelayed(this, 60_000);
        }
    };
    private ListView list;
    private boolean loading;
    private int sortMode; // 0: proximity (default), 1: contribution, 2: name, 3: rating
    private String restoredQuery = "";
    private int detailGeneration;
    private String openRestaurantId;
    private MenuTranslator menuTranslator;
    private MenuSpeaker menuSpeaker;
    private RestaurantClassifier restaurantClassifier;
    private boolean classificationEnabled;
    private final Map<String, List<RestaurantTaxonomy.Tag>> classifications = new LinkedHashMap<>();
    private String classificationError = "";
    private TextView classificationStatus;
    private boolean translationEnabled, allowMobileModels, showOriginalMenus;
    private String translationEngine;
    private LinearLayout currentMenuBody;
    private List<String[]> currentMenus;
    private int menuRenderVersion;

    @Override protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("MainActivity", MODE_PRIVATE).getString("appLanguage", "en");
        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle state) {
        themeMode = getPreferences(MODE_PRIVATE).getString("colorTheme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        darkTheme = themeMode.equals("dark") || (themeMode.equals("system") && systemDark);
        setTheme(darkTheme ? R.style.AppTheme_Dark : R.style.AppTheme);
        BG = darkTheme ? 0xFF121C25 : 0xFFF8FAFC;
        INK = darkTheme ? 0xFFE5EDF4 : 0xFF172E46;
        MUTED = darkTheme ? 0xFFA4B3C1 : 0xFF53677A;
        GREEN = darkTheme ? 0xFF73C7A3 : 0xFF176449;
        LINE = darkTheme ? 0xFF2C3B49 : 0xFFE0E7ED;
        RED = darkTheme ? 0xFFF08B88 : 0xFFB04343;
        GOLD = darkTheme ? 0xFFF2C14E : 0xFFA87300;
        super.onCreate(state);
        translationEnabled = getPreferences(MODE_PRIVATE).getBoolean("translateMenus", true);
        allowMobileModels = getPreferences(MODE_PRIVATE).getBoolean("mobileModels", false);
        menuSpeaker = new MenuSpeaker(this);
        menuTranslator = new MenuTranslator(this, getPreferences(MODE_PRIVATE).getString("appLanguage", "en"));
        translationEngine = getPreferences(MODE_PRIVATE).getString("translationEngine", "llm");
        configureTranslator();
        restaurantClassifier = new RestaurantClassifier(this, new RestaurantClassifier.Listener() {
            @Override public void onClassification(String id, List<RestaurantTaxonomy.Tag> labels) {
                classifications.put(id, labels);
                scheduleFilter();
            }
            @Override public void onError(String message) {
                if (classificationError.equals(message)) return;
                classificationError = message;
                String text = getString(R.string.classification_failed, message);
                if (classificationStatus != null) {
                    classificationStatus.setText(text);
                    classificationStatus.setVisibility(View.VISIBLE);
                }
                if (status != null) {
                    setStatus(text);
                    status.setOnClickListener(v -> showSettings());
                }
            }
        });
        configureClassification();
        hoursRepository = new HoursRepository(this, new HoursRepository.Listener() {
            @Override public void onCached(Map<String, String> schedules, Map<String, Boolean> menus,
                                           Map<String, OpeningHours.Status> states) {
                hoursSchedules.putAll(schedules);
                menuAvailability.putAll(menus);
                availability.putAll(states);
                scheduleFilter();
            }
            @Override public void onHours(String id, String schedule) {
                if (schedule != null) hoursSchedules.put(id, schedule);
                else hoursSchedules.remove(id);
                availability.put(id, OpeningHours.status(schedule));
                scheduleFilter();
            }
            @Override public void onMenu(String id, Boolean hasMenu, MenuEvidence evidence) {
                if (hasMenu == null) menuAvailability.remove(id);
                else menuAvailability.put(id, hasMenu);
                if (evidence != null) restaurantClassifier.rememberMenu(id, evidence);
                scheduleFilter();
            }
            @Override public void onLoading(boolean active) {
                if (hoursLoading == active) return;
                hoursLoading = active;
                scheduleFilter();
            }
        });
        favorites = new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("favorites", java.util.Collections.emptySet()));
        if (state != null) {
            onlyFavorites = state.getBoolean("onlyFavorites");
            showClosed = state.getBoolean("showClosed");
            showWithoutMenu = state.getBoolean("showWithoutMenu");
            sortMode = state.getInt("sortMode", 0);
            restoredQuery = state.getString("query", "");
            searchOpen = state.getBoolean("searchOpen");
            ArrayList<String> services = state.getStringArrayList("services");
            if (services != null) selectedServices.addAll(services);
            minPrice = state.getInt("minPrice", 0);
            maxPrice = state.getInt("maxPrice", Integer.MAX_VALUE);
            minRating = state.getInt("minRating", 0);
            radiusMeters = state.getDouble("radiusMeters", Double.POSITIVE_INFINITY);
        }
        root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        buildHome();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        loadRestaurants();
        if (state != null && state.getBoolean("settingsOpen")) showSettings();
        if (!hasLocationPermission() && !getPreferences(MODE_PRIVATE).getBoolean("locationAsked", false)) {
            requestLocationAccess();
        }
    }

    private void buildHome() {
        home = column();
        root.addView(home, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout header = column();
        header.setPadding(dp(20), dp(4), dp(20), 0);
        home.addView(header);

        LinearLayout brand = row();
        TextView title = text("betterboni", 22, INK, true);
        brand.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        searchButton = toolbarButton(searchOpen ? R.drawable.ic_close : R.drawable.ic_search,
                getString(searchOpen ? R.string.search_close : R.string.search_restaurant));
        searchButton.setOnClickListener(v -> setSearchOpen(!searchOpen));
        brand.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        filterButton = toolbarButton(R.drawable.ic_filter, getString(R.string.filter));
        filterButton.setEnabled(false);
        filterButton.setOnClickListener(v -> showFilters());
        updateFilterButton();
        brand.addView(filterButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton settings = toolbarButton(R.drawable.ic_settings, getString(R.string.settings));
        settings.setOnClickListener(v -> showSettings());
        brand.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(brand);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setHint(R.string.search_restaurant);
        search.setPadding(0, dp(8), 0, dp(8));
        search.setText(restoredQuery);
        search.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
        header.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

        locationStatus = text("", 12, GREEN, false);
        locationStatus.setVisibility(View.GONE);
        locationStatus.setMinHeight(dp(48));
        locationStatus.setGravity(Gravity.CENTER_VERTICAL);
        locationStatus.setOnClickListener(v -> {
            if (!hasLocationPermission()) requestLocationAccess();
            else if (!locationManager.isLocationEnabled()) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            else startLocation();
        });
        header.addView(locationStatus);
        status = text("", 12, MUTED, false);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(status);

        empty = text("", 16, MUTED, false);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        empty.setVisibility(View.GONE);
        home.addView(empty);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setClipToPadding(false);
        list.setPadding(dp(20), 0, dp(20), dp(8));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showDetail(visible.get(position)));
        FrameLayout content = new FrameLayout(this);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        startupSpinner = new ProgressBar(this);
        startupSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        startupSpinner.setContentDescription(getString(R.string.loading_restaurants));
        content.addView(startupSpinner, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        home.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable s) {}
        });
    }

    private void showSettings() {
        if (settingsPage != null) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.GONE);
        settingsPage = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(20));
        settingsPage.addView(form);
        root.addView(settingsPage, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout heading = row();
        ImageButton back = toolbarButton(R.drawable.ic_back, getString(R.string.settings_close));
        back.setOnClickListener(v -> closeSettings());
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        heading.addView(text(getString(R.string.settings), 22, INK, true));
        margin(form, heading, 0, 16);
        margin(form, text(getString(R.string.general), 16, INK, true), 0, 8);
        LinearLayout languageRow = row();
        languageRow.addView(text(getString(R.string.app_language), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner appLanguage = new Spinner(this);
        appLanguage.setContentDescription(getString(R.string.app_language));
        ArrayAdapter<String> languages = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"English", "Deutsch"});
        languages.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appLanguage.setAdapter(languages);
        appLanguage.setSelection(getPreferences(MODE_PRIVATE).getString("appLanguage", "en").equals("de") ? 1 : 0);
        languageRow.addView(appLanguage, new LinearLayout.LayoutParams(dp(140), dp(48)));
        form.addView(languageRow);
        appLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String selected = index == 1 ? "de" : "en";
                if (selected.equals(getPreferences(MODE_PRIVATE).getString("appLanguage", "en"))) return;
                getPreferences(MODE_PRIVATE).edit().putString("appLanguage", selected).apply();
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout themeRow = row();
        themeRow.addView(text(getString(R.string.color_theme), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner theme = new Spinner(this);
        theme.setContentDescription(getString(R.string.theme_select));
        String[] themeValues = {"system", "light", "dark"};
        ArrayAdapter<String> themes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.themes));
        themes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        theme.setAdapter(themes);
        theme.setSelection(themeMode.equals("light") ? 1 : themeMode.equals("dark") ? 2 : 0);
        themeRow.addView(theme, new LinearLayout.LayoutParams(dp(140), dp(48)));
        form.addView(themeRow);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String selected = themeValues[index];
                if (selected.equals(themeMode)) return;
                getPreferences(MODE_PRIVATE).edit().putString("colorTheme", selected).apply();
                configureTranslator();
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        margin(form, text(getString(R.string.translation), 16, INK, true), 24, 8);
        LinearLayout engineRow = row();
        engineRow.addView(text(getString(R.string.translation_method), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner engine = new Spinner(this);
        engine.setContentDescription(getString(R.string.translation_method_select));
        ArrayAdapter<String> engines = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.translation_methods));
        engines.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engine.setAdapter(engines);
        engine.setSelection(isLlm() ? 1 : 0);
        engineRow.addView(engine, new LinearLayout.LayoutParams(dp(208), dp(48)));
        form.addView(engineRow);
        CheckBox automatic = new CheckBox(this);
        automatic.setText(R.string.translate_auto);
        automatic.setChecked(translationEnabled);
        automatic.setMinHeight(dp(48));
        form.addView(automatic);
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            translationEnabled = enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("translateMenus", enabled).apply();
            refreshMenuTranslation();
        });
        CheckBox mobile = new CheckBox(this);
        mobile.setText(R.string.download_mobile);
        mobile.setChecked(allowMobileModels);
        mobile.setMinHeight(dp(48));
        form.addView(mobile);
        mobile.setOnCheckedChangeListener((button, enabled) -> {
            allowMobileModels = enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("mobileModels", enabled).apply();
            refreshMenuTranslation();
        });
        LinearLayout llmForm = column();
        llmField(llmForm, getString(R.string.endpoint_url), LlmEndpoint.DEFAULT_URL, "llmEndpoint", false, LlmEndpoint.DEFAULT_URL);
        llmField(llmForm, getString(R.string.model), getString(R.string.model_id), "llmModel", false, LlmEndpoint.DEFAULT_MODEL);
        LinearLayout reasoningRow = row();
        reasoningRow.addView(text(getString(R.string.reasoning), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner reasoning = new Spinner(this);
        reasoning.setContentDescription(getString(R.string.reasoning_select));
        String[] reasoningValues = {"", "none", "low", "medium", "high"};
        ArrayAdapter<String> efforts = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.reasoning_levels));
        efforts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reasoning.setAdapter(efforts);
        String savedReasoning = getPreferences(MODE_PRIVATE).getString("llmReasoning", "");
        for (int i = 0; i < reasoningValues.length; i++) {
            if (reasoningValues[i].equals(savedReasoning)) reasoning.setSelection(i);
        }
        reasoningRow.addView(reasoning, new LinearLayout.LayoutParams(dp(192), dp(48)));
        margin(llmForm, reasoningRow, 8, 0);
        reasoning.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String selected = reasoningValues[index];
                if (selected.equals(getPreferences(MODE_PRIVATE).getString("llmReasoning", ""))) return;
                getPreferences(MODE_PRIVATE).edit().putString("llmReasoning", selected).apply();
                configureTranslator();
                ++menuRenderVersion;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        form.addView(llmForm);
        mobile.setVisibility(isLlm() ? View.GONE : View.VISIBLE);
        llmForm.setVisibility(isLlm() ? View.VISIBLE : View.GONE);
        engine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String selected = index == 1 ? "llm" : "google";
                if (selected.equals(translationEngine)) return;
                translationEngine = selected;
                getPreferences(MODE_PRIVATE).edit().putString("translationEngine", selected).apply();
                configureTranslator();
                ++menuRenderVersion;
                mobile.setVisibility(isLlm() ? View.GONE : View.VISIBLE);
                llmForm.setVisibility(isLlm() ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        margin(form, text(getString(R.string.classification), 16, INK, true), 24, 8);
        CheckBox classify = new CheckBox(this);
        classify.setText(R.string.classification_enabled);
        classify.setChecked(classificationEnabled);
        classify.setMinHeight(dp(48));
        form.addView(classify);
        LinearLayout classifierForm = column();
        LinearLayout apiRow = row();
        apiRow.addView(text(getString(R.string.classification_api), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner api = new Spinner(this);
        api.setContentDescription(getString(R.string.classification_api));
        ArrayAdapter<String> apis = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Decisions (Jev)", "Chat Completions"});
        apis.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        api.setAdapter(apis);
        api.setSelection(getPreferences(MODE_PRIVATE).getString("classificationApi", "decisions").equals("chat") ? 1 : 0);
        apiRow.addView(api, new LinearLayout.LayoutParams(dp(192), dp(48)));
        classifierForm.addView(apiRow);
        String translationUrl = getPreferences(MODE_PRIVATE).getString("llmEndpoint", LlmEndpoint.DEFAULT_URL);
        String protocol = getPreferences(MODE_PRIVATE).getString("classificationApi", "decisions");
        EditText classifierUrl = llmField(classifierForm, getString(R.string.classification_endpoint),
                "https://…/api/alpha/decisions", "classificationEndpoint", false,
                ClassificationEndpoint.defaultUrl(translationUrl, protocol));
        llmField(classifierForm, getString(R.string.classification_model), ClassificationEndpoint.DEFAULT_MODEL,
                "classificationModel", false, ClassificationEndpoint.DEFAULT_MODEL);
        form.addView(classifierForm);
        classifierForm.setVisibility(classificationEnabled ? View.VISIBLE : View.GONE);
        classify.setOnCheckedChangeListener((button, enabled) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("classificationEnabled", enabled).apply();
            configureClassification();
            classifierForm.setVisibility(enabled ? View.VISIBLE : View.GONE);
            filter();
        });
        api.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                String selected = index == 1 ? "chat" : "decisions";
                String old = getPreferences(MODE_PRIVATE).getString("classificationApi", "decisions");
                if (old.equals(selected)) return;
                if (classifierUrl.getText().toString().equals(ClassificationEndpoint.defaultUrl(translationUrl, old))) {
                    classifierUrl.setText(ClassificationEndpoint.defaultUrl(translationUrl, selected));
                }
                getPreferences(MODE_PRIVATE).edit().putString("classificationApi", selected).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        classificationStatus = text(classificationError.isEmpty() ? "" : getString(R.string.classification_failed, classificationError), 12, RED, false);
        classificationStatus.setVisibility(classificationError.isEmpty() ? View.GONE : View.VISIBLE);
        classificationStatus.setOnClickListener(v -> {
            configureClassification();
            restaurantClassifier.retry();
            warmClassifications();
        });
        form.addView(classificationStatus);
        margin(form, text(getString(R.string.speech_section), 16, INK, true), 24, 8);
        llmField(form, getString(R.string.speech_model), MenuSpeaker.DEFAULT_MODEL,
                "speechModel", false, MenuSpeaker.DEFAULT_MODEL);
        llmField(form, getString(R.string.shared_api_key), getString(R.string.bearer_token), "llmKeyEncrypted", true);
    }

    private boolean isLlm() { return "llm".equals(translationEngine); }

    private void configureTranslator() {
        String key = "";
        try { key = EndpointSecret.load(getPreferences(MODE_PRIVATE)); }
        catch (Exception error) { Toast.makeText(this, R.string.key_read_error, Toast.LENGTH_LONG).show(); }
        menuTranslator.configure(isLlm(), new LlmEndpoint(
                getPreferences(MODE_PRIVATE).getString("llmEndpoint", LlmEndpoint.DEFAULT_URL),
                getPreferences(MODE_PRIVATE).getString("llmModel", LlmEndpoint.DEFAULT_MODEL), key,
                getPreferences(MODE_PRIVATE).getString("llmReasoning", ""),
                getPreferences(MODE_PRIVATE).getString("appLanguage", "en")));
        menuSpeaker.configure(getPreferences(MODE_PRIVATE).getString("speechModel", MenuSpeaker.DEFAULT_MODEL), key);
    }

    private void configureClassification() {
        classificationEnabled = getPreferences(MODE_PRIVATE).getBoolean("classificationEnabled", true);
        String protocol = getPreferences(MODE_PRIVATE).getString("classificationApi", "decisions");
        String url = getPreferences(MODE_PRIVATE).getString("classificationEndpoint",
                ClassificationEndpoint.defaultUrl(getPreferences(MODE_PRIVATE).getString("llmEndpoint", LlmEndpoint.DEFAULT_URL), protocol));
        String key = "";
        try { key = EndpointSecret.load(getPreferences(MODE_PRIVATE)); }
        catch (Exception error) { Toast.makeText(this, R.string.key_read_error, Toast.LENGTH_LONG).show(); }
        ClassificationEndpoint config = new ClassificationEndpoint(url,
                getPreferences(MODE_PRIVATE).getString("classificationModel", ClassificationEndpoint.DEFAULT_MODEL), key,
                protocol, getPreferences(MODE_PRIVATE).getString("appLanguage", "en"));
        if (restaurantClassifier.configure(classificationEnabled && !url.isEmpty(), config)) {
            classifications.clear();
            if (!classificationError.isEmpty() && status != null
                    && status.getText().toString().equals(getString(R.string.classification_failed, classificationError))) setStatus("");
            classificationError = "";
            if (classificationStatus != null) classificationStatus.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        }
    }

    private void warmClassifications() {
        if (!classificationEnabled || settingsPage != null) return;
        for (int i = 0; i < Math.min(12, visible.size()); i++) {
            Restaurant restaurant = visible.get(i);
            if (Boolean.TRUE.equals(menuAvailability.get(restaurant.id))) restaurantClassifier.request(restaurant);
        }
    }

    private void llmField(LinearLayout form, String label, String hint, String preference, boolean secret) {
        llmField(form, label, hint, preference, secret, "");
    }

    private EditText llmField(LinearLayout form, String label, String hint, String preference, boolean secret, String fallback) {
        margin(form, text(label, 12, MUTED, false), 12, 0);
        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setContentDescription(label);
        input.setInputType(secret
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (secret) {
            input.setSaveEnabled(false);
            try { input.setText(EndpointSecret.load(getPreferences(MODE_PRIVATE))); }
            catch (Exception error) { input.setError(getString(R.string.key_reenter)); }
        } else input.setText(getPreferences(MODE_PRIVATE).getString(preference, fallback));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) {
                if (secret) {
                    try { EndpointSecret.save(getPreferences(MODE_PRIVATE), value.toString()); input.setError(null); }
                    catch (Exception error) { input.setError(getString(R.string.key_save_error)); }
                } else getPreferences(MODE_PRIVATE).edit().putString(preference, value.toString().trim()).apply();
            }
        });
        form.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
        return input;
    }

    private void refreshMenuTranslation() {
        if (settingsPage != null) return;
        if (currentMenuBody != null && currentMenus != null && openRestaurantId != null) {
            showOriginalMenus = false;
            renderMenus(currentMenuBody, currentMenus);
        }
    }

    private void closeSettings() {
        if (settingsPage == null) return;
        root.removeView(settingsPage);
        settingsPage = null;
        classificationStatus = null;
        configureTranslator();
        configureClassification();
        refreshMenuTranslation();
        filter();
        for (int i = 0; i < root.getChildCount(); i++) {
            View page = root.getChildAt(i);
            page.setVisibility(page == home && openRestaurantId != null ? View.GONE : View.VISIBLE);
        }
    }

    private void setSearchOpen(boolean open) {
        searchOpen = open;
        search.setVisibility(open ? View.VISIBLE : View.GONE);
        searchButton.setImageResource(open ? R.drawable.ic_close : R.drawable.ic_search);
        searchButton.setContentDescription(getString(open ? R.string.search_close : R.string.search_restaurant));
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (open) {
            search.requestFocus();
            search.post(() -> {
                if (searchOpen) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            });
        } else {
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
            search.setText("");
        }
    }

    private void loadRestaurants() {
        if (loading) return;
        loading = true;
        hoursRepository.retryFailures();
        restaurantClassifier.retry();
        setStatus("");
        updateStartupLoading();
        network.execute(() -> {
            File cache = new File(getCacheDir(), "restaurants.html");
            if (restaurants.isEmpty() && cache.isFile()) {
                try {
                    Document document = Jsoup.parse(cache, "UTF-8");
                    List<Restaurant> cached = Restaurant.parse(document);
                    Map<String, String> options = Restaurant.filterOptions(document, getResources());
                    onUi(() -> display(cached, options, ""));
                } catch (Exception ignored) { /* Fetch a fresh copy if the cache cannot be read. */ }
            }
            try {
                Document document = fetch(Restaurant.SOURCE + "/sl/restaurant");
                List<Restaurant> fresh = Restaurant.parse(document);
                Map<String, String> options = Restaurant.filterOptions(document, getResources());
                try {
                    Files.write(cache.toPath(), document.outerHtml().getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { /* A cache write must not prevent showing live data. */ }
                onUi(() -> display(fresh, options, ""));
            } catch (Exception error) {
                android.util.Log.w("BetterBoni", "Restaurant fetch failed", error);
                onUi(() -> {
                    setStatus(restaurants.isEmpty()
                            ? getString(R.string.loading_failed)
                            : getString(R.string.offline_cached));
                    filter();
                });
            } finally {
                onUi(() -> {
                    loading = false;
                    updateStartupLoading();
                });
            }
        });
    }

    private void display(List<Restaurant> items, Map<String, String> options, String message) {
        restaurants.clear();
        restaurants.addAll(items);
        serviceOptions.clear();
        serviceOptions.putAll(options);
        filterButton.setEnabled(true);
        setStatus(message);
        hoursRepository.load(items, position);
        scheduleFilter();
    }

    private void scheduleFilter() {
        hoursClock.removeCallbacks(renderRestaurants);
        hoursClock.postDelayed(renderRestaurants, 16);
    }

    private void filter() {
        if (search == null) return;
        String query = Restaurant.searchable(search.getText().toString());
        visible.clear();
        for (Restaurant item : restaurants) {
            OpeningHours.Status open = availability.getOrDefault(item.id, OpeningHours.Status.UNKNOWN);
            if (open == OpeningHours.Status.UNKNOWN) continue;
            if (!showWithoutMenu && !Boolean.TRUE.equals(menuAvailability.get(item.id))) continue;
            if (onlyFavorites && !favorites.contains(item.id)) continue;
            if (!item.services.containsAll(selectedServices)) continue;
            if (item.contribution < minPrice || item.contribution > maxPrice) continue;
            if (item.rating < minRating) continue;
            if (Double.isFinite(radiusMeters) && (position == null || item.distanceFrom(position) > radiusMeters)) continue;
            if (!query.isEmpty() && !Restaurant.searchable(item.name).contains(query)) continue;
            visible.add(item);
        }
        if (!showClosed && query.isEmpty()
                && visible.stream().anyMatch(item -> availability.get(item.id) == OpeningHours.Status.OPEN)) {
            visible.removeIf(item -> availability.get(item.id) == OpeningHours.Status.CLOSED);
        }
        Comparator<Restaurant> names = Comparator.comparing(r -> Restaurant.searchable(r.name));
        Comparator<Restaurant> order = names;
        if (sortMode == 0 && position != null) {
            order = Comparator.comparingDouble((Restaurant r) -> r.distanceFrom(position)).thenComparing(names);
        } else if (sortMode == 1) {
            order = Comparator.comparingInt((Restaurant r) -> r.contribution).thenComparing(names);
        } else if (sortMode == 3) {
            order = Comparator.comparingInt((Restaurant r) -> r.rating).reversed().thenComparing(names);
        }
        visible.sort(order);
        empty.setText(restaurants.isEmpty()
                ? getString(R.string.no_data)
                : Double.isFinite(radiusMeters) && position == null
                    ? getString(R.string.radius_needs_location)
                    : onlyFavorites && favorites.isEmpty()
                        ? getString(R.string.no_favorites)
                        : !showClosed && query.isEmpty() && selectedServices.isEmpty()
                            ? getString(showWithoutMenu ? R.string.no_open_restaurants : R.string.no_open_restaurants_with_menu)
                            : getString(R.string.no_results));
        empty.setVisibility(visible.isEmpty() && !restaurants.isEmpty() && !hoursLoading ? View.VISIBLE : View.GONE);
        updateStartupLoading();
        adapter.notifyDataSetChanged();
        warmClassifications();
    }

    private void updateFilterButton() {
        int active = selectedServices.size() + (minPrice > 0 ? 1 : 0)
                + (maxPrice < Integer.MAX_VALUE ? 1 : 0) + (minRating > 0 ? 1 : 0)
                + (Double.isFinite(radiusMeters) ? 1 : 0) + (onlyFavorites ? 1 : 0) + (showClosed ? 1 : 0) + (showWithoutMenu ? 1 : 0);
        filterButton.setImageResource(active == 0 ? R.drawable.ic_filter : R.drawable.ic_filter_active);
        filterButton.setStateDescription(active == 0 ? getString(R.string.no_active_filters) : getString(R.string.active_filters, active));
        filterButton.setContentDescription(getString(R.string.filter_accessibility, active, sortLabel()));
    }

    private void showFilters() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        Map<String, ToggleButton> checks = new LinkedHashMap<>();
        String[][] groups = {{getString(R.string.food), "12", "22", "23", "7"},
                {getString(R.string.diet), "1", "10"}, {getString(R.string.service), "5", "8", "20"}};
        for (String[] group : groups) {
            FilterFlowLayout tiles = new FilterFlowLayout(this);
            boolean headingAdded = false;
            for (int i = 1; i < group.length; i++) {
                String id = group[i];
                if (!serviceOptions.containsKey(id)) continue;
                if (!headingAdded) {
                    margin(form, text(group[0], 12, MUTED, true), checks.isEmpty() ? 4 : 12, 6);
                    headingAdded = true;
                }
                checks.put(id, addServiceFilter(tiles, id, serviceOptions.get(id)));
            }
            if (tiles.getChildCount() > 0) form.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        }
        margin(form, text(getString(R.string.other), 12, MUTED, true), 12, 6);
        FilterFlowLayout metaTiles = new FilterFlowLayout(this);
        for (Map.Entry<String, String> option : serviceOptions.entrySet()) {
            if (!checks.containsKey(option.getKey())) {
                checks.put(option.getKey(), addServiceFilter(metaTiles, option.getKey(), option.getValue()));
            }
        }
        ToggleButton favoritesOnly = filterTile(getString(R.string.favorites_only), onlyFavorites);
        metaTiles.addView(favoritesOnly);
        ToggleButton closed = filterTile(getString(R.string.show_closed), showClosed);
        metaTiles.addView(closed);
        ToggleButton withoutMenu = filterTile(getString(R.string.show_without_menu), showWithoutMenu);
        metaTiles.addView(withoutMenu);
        form.addView(metaTiles, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout sortRow = row();
        sortRow.addView(text(getString(R.string.sort_by), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner sorting = new Spinner(this);
        sorting.setContentDescription(getString(R.string.sort_order));
        ArrayAdapter<String> orders = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.sort_options));
        orders.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sorting.setAdapter(orders);
        sorting.setSelection(sortMode);
        sortRow.addView(sorting, new LinearLayout.LayoutParams(dp(152), dp(48)));
        form.addView(sortRow);
        EditText from = numberField(form, getString(R.string.price_min), "€", minPrice == 0 ? "" : decimal(minPrice / 100.0));
        EditText to = numberField(form, getString(R.string.price_max), "€", maxPrice == Integer.MAX_VALUE ? "" : decimal(maxPrice / 100.0));
        EditText radius = numberField(form, getString(R.string.radius), "km", Double.isFinite(radiusMeters) ? decimal(radiusMeters / 1000) : "");
        LinearLayout ratingRow = row();
        ratingRow.addView(text(getString(R.string.rating), 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Spinner rating = new Spinner(this);
        rating.setContentDescription(getString(R.string.rating_min));
        ArrayAdapter<String> ratings = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.rating_options));
        ratings.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rating.setAdapter(ratings);
        rating.setSelection(minRating);
        ratingRow.addView(rating, new LinearLayout.LayoutParams(dp(112), dp(48)));
        form.addView(ratingRow);
        LinearLayout header = row();
        header.setPadding(dp(20), dp(8), dp(12), dp(4));
        header.addView(text(getString(R.string.filter), 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reset = toolbarButton(R.drawable.ic_reset, getString(R.string.reset_filters));
        reset.setTooltipText(getString(R.string.reset_filters));
        header.addView(reset, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        boolean[] resetting = {false};
        Runnable applyFilters = () -> {
            if (resetting[0]) return;
            int previousSort = sortMode;
            double previousRadius = radiusMeters;
            selectedServices.clear();
            checks.forEach((id, check) -> { if (check.isChecked()) selectedServices.add(id); });
            sortMode = sorting.getSelectedItemPosition();
            onlyFavorites = favoritesOnly.isChecked();
            showClosed = closed.isChecked();
            showWithoutMenu = withoutMenu.isChecked();
            minRating = rating.getSelectedItemPosition();
            from.setError(null);
            to.setError(null);
            radius.setError(null);
            try {
                int low = readPrice(from, 0), high = readPrice(to, Integer.MAX_VALUE);
                double distance = readRadius(radius);
                if (low <= high) {
                    minPrice = low;
                    maxPrice = high;
                    radiusMeters = distance;
                } else to.setError(getString(R.string.price_range_error));
            } catch (IllegalArgumentException ignored) {
                // Keep the last valid numeric limits; other filters still update immediately.
            }
            updateFilterButton();
            filter();
            if ((sortMode == 0 && previousSort != 0)
                    || (Double.isFinite(radiusMeters) && previousRadius != radiusMeters)) startLocation();
        };
        checks.values().forEach(tile -> tile.setOnCheckedChangeListener((button, checked) -> applyFilters.run()));
        favoritesOnly.setOnCheckedChangeListener((button, checked) -> applyFilters.run());
        closed.setOnCheckedChangeListener((button, checked) -> applyFilters.run());
        withoutMenu.setOnCheckedChangeListener((button, checked) -> applyFilters.run());
        AdapterView.OnItemSelectedListener selectionChanged = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { applyFilters.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        sorting.setOnItemSelectedListener(selectionChanged);
        rating.setOnItemSelectedListener(selectionChanged);
        TextWatcher numberChanged = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) { applyFilters.run(); }
        };
        from.addTextChangedListener(numberChanged);
        to.addTextChangedListener(numberChanged);
        radius.addTextChangedListener(numberChanged);
        reset.setOnClickListener(v -> {
            resetting[0] = true;
            sorting.setSelection(0);
            favoritesOnly.setChecked(false);
            closed.setChecked(false);
            withoutMenu.setChecked(false);
            from.setText("");
            to.setText("");
            radius.setText("");
            rating.setSelection(0);
            checks.values().forEach(check -> check.setChecked(false));
            resetting[0] = false;
            applyFilters.run();
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setView(scroll)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        android.view.Window window = dialog.getWindow();
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        android.graphics.Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        window.setLayout((int) (bounds.width() * 0.90f), (int) (bounds.height() * 0.78f));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(BG);
        panel.setCornerRadius(dp(20));
        window.setBackgroundDrawable(panel);
        window.getDecorView().setClipToOutline(true);
    }

    private ToggleButton addServiceFilter(FilterFlowLayout tiles, String id, String label) {
        String shortLabel = switch (id) {
            case "1" -> getString(R.string.veg_short);
            case "8" -> getString(R.string.accessible);
            case "10" -> getString(R.string.gluten_free_short);
            case "20" -> getString(R.string.weekend_short);
            case "69" -> getString(R.string.new_restaurant);
            default -> label;
        };
        ToggleButton tile = filterTile(shortLabel, selectedServices.contains(id));
        tile.setContentDescription(label);
        if (id.equals("10")) tile.setTooltipText(getString(R.string.gluten_note));
        if (id.equals("12")) tile.setTooltipText(getString(R.string.healthy_note));
        tiles.addView(tile);
        return tile;
    }

    private ToggleButton filterTile(String label, boolean selected) {
        ToggleButton tile = new ToggleButton(this);
        tile.setTextOn(label);
        tile.setTextOff(label);
        tile.setChecked(selected);
        tile.setAllCaps(false);
        tile.setTextSize(13);
        tile.setSingleLine(false);
        tile.setMaxLines(2);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        tile.setMinHeight(dp(48));
        tile.setMinimumHeight(dp(48));
        tile.setMinWidth(dp(48));
        tile.setMinimumWidth(dp(48));
        tile.setStateListAnimator(null);
        tile.setBackgroundTintList(null);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_checked}, filterTileShape(GREEN, false));
        background.addState(new int[]{android.R.attr.state_focused}, filterTileShape(darkTheme ? 0xFF1B2A36 : 0xFFE9EFF4, true));
        background.addState(new int[]{}, filterTileShape(darkTheme ? 0xFF1B2A36 : 0xFFE9EFF4, false));
        tile.setBackground(background);
        tile.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{BG, INK}));
        return tile;
    }

    private GradientDrawable filterTileShape(int color, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));
        if (focused) shape.setStroke(dp(1), GREEN);
        return shape;
    }

    private EditText numberField(LinearLayout form, String label, String unit, String value) {
        LinearLayout line = row();
        line.addView(text(label, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(unit);
        input.setContentDescription(getString(R.string.field_unit, label, unit));
        input.setText(value);
        line.addView(input, new LinearLayout.LayoutParams(dp(112), dp(48)));
        form.addView(line);
        return input;
    }

    private String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', Locale.getDefault().getLanguage().equals("de") ? ',' : '.');
    }

    private int readPrice(EditText field, int fallback) {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return fallback;
        try {
            int cents = new BigDecimal(value.replace(',', '.')).movePointRight(2).intValueExact();
            if (cents < 0) throw new NumberFormatException();
            return cents;
        } catch (NumberFormatException | ArithmeticException error) {
            field.setError(getString(R.string.price_error));
            throw new IllegalArgumentException(error);
        }
    }

    private double readRadius(EditText field) {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return Double.POSITIVE_INFINITY;
        try {
            double meters = new BigDecimal(value.replace(',', '.')).doubleValue() * 1000;
            if (!Double.isFinite(meters) || meters <= 0) throw new NumberFormatException();
            return meters;
        } catch (NumberFormatException error) {
            field.setError(getString(R.string.radius_error));
            throw new IllegalArgumentException(error);
        }
    }

    private Button favoriteButton() {
        Button star = button("☆", false);
        star.setTextSize(24);
        star.setMinWidth(dp(48));
        star.setMinimumWidth(dp(48));
        star.setPadding(0, 0, 0, 0);
        star.setFocusable(false);
        return star;
    }

    private void bindRestaurantName(TextView title, Restaurant restaurant, int iconSize) {
        if (restaurant.vegetarian) {
            title.setText(restaurant.name);
            title.setContentDescription(restaurant.name);
            return;
        }
        SpannableString name = new SpannableString(restaurant.name + "\u00A0\uFFFC");
        Drawable marker = getDrawable(R.drawable.ic_vegetarian_unmarked).mutate();
        marker.setTint(MUTED);
        marker.setBounds(0, 0, dp(iconSize), dp(iconSize));
        name.setSpan(new ImageSpan(marker, ImageSpan.ALIGN_BASELINE), name.length() - 1, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(name);
        title.setContentDescription(getString(R.string.restaurant_without_vegetarian_label, restaurant.name));
    }

    private int restaurantAccent(Restaurant restaurant) {
        return switch (availability.getOrDefault(restaurant.id, OpeningHours.Status.UNKNOWN)) {
            case OPEN -> GREEN;
            case CLOSED -> RED;
            case UNKNOWN -> MUTED;
        };
    }

    private void bindFavorite(Button star, Restaurant restaurant) {
        boolean saved = favorites.contains(restaurant.id);
        star.setText(saved ? "★" : "☆");
        star.setTextColor(saved ? GOLD : MUTED);
        star.setContentDescription(getString(saved ? R.string.favorite_item_remove : R.string.favorite_item_save, restaurant.name));
        star.setStateDescription(getString(saved ? R.string.saved : R.string.not_saved));
        star.setOnClickListener(v -> {
            if (!favorites.remove(restaurant.id)) favorites.add(restaurant.id);
            getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
            bindFavorite(star, restaurant);
            filter();
        });
    }

    private void bindDetailFavorite(ImageButton star, Restaurant restaurant) {
        boolean saved = favorites.contains(restaurant.id);
        star.setImageResource(saved ? R.drawable.ic_star_filled : R.drawable.ic_star);
        star.setColorFilter(saved ? GOLD : restaurantAccent(restaurant));
        star.setContentDescription(getString(saved ? R.string.favorite_remove : R.string.favorite_save));
        star.setOnClickListener(v -> {
            if (!favorites.remove(restaurant.id)) favorites.add(restaurant.id);
            getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
            bindDetailFavorite(star, restaurant);
            filter();
        });
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateStartupLoading() {
        boolean initialLoad = (loading && restaurants.isEmpty()) || (hoursLoading && visible.isEmpty());
        startupSpinner.setVisibility(initialLoad ? View.VISIBLE : View.GONE);
        locationStatus.setVisibility(!initialLoad && locationStatus.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void setLocationStatus(String message) {
        locationStatus.setText(message);
        updateStartupLoading();
    }

    private String sortLabel() {
        return switch (sortMode) {
            case 0 -> getString(R.string.distance);
            case 1 -> getString(R.string.price_ascending);
            case 3 -> getString(R.string.rating);
            default -> "A–Z";
        };
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationAccess() {
        if (getPreferences(MODE_PRIVATE).getBoolean("locationAsked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean("locationAsked", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) startLocation();
    }

    @Override protected void onStart() {
        super.onStart();
        startLocation();
        hoursClock.post(hoursTick);
    }

    @Override protected void onStop() {
        menuSpeaker.stop();
        hoursClock.removeCallbacks(hoursTick);
        locationManager.removeUpdates(locationListener);
        super.onStop();
    }

    private void startLocation() {
        locationManager.removeUpdates(locationListener);
        if (!hasLocationPermission()) {
            position = null;
            setLocationStatus(getString(R.string.location_permission));
            filter();
            return;
        }
        if (!locationManager.isLocationEnabled()) {
            position = null;
            setLocationStatus(getString(R.string.location_enable));
            filter();
            return;
        }
        // Never use an old cached fix as if it were the current position.
        if (position != null && SystemClock.elapsedRealtimeNanos() - position.getElapsedRealtimeNanos() > 300_000_000_000L) {
            position = null;
        }
        setLocationStatus("");
        boolean subscribed = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue;
                locationManager.requestLocationUpdates(provider, 10000, 25, locationListener);
                subscribed = true;
                Location cached = locationManager.getLastKnownLocation(provider);
                if (cached != null) updatePosition(cached);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Approximate permission may not allow every provider.
            }
        }
        if (!subscribed && position == null) setLocationStatus(getString(R.string.location_retry));
        filter();
    }

    private void updatePosition(Location location) {
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        if (age > 300_000_000_000L || age < 0) return;
        if (position != null && location.getElapsedRealtimeNanos() < position.getElapsedRealtimeNanos()) return;
        position = location;
        setLocationStatus("");
        filter();
    }

    private String distanceLabel(Restaurant restaurant) {
        double meters = restaurant.distanceFrom(position);
        if (!Double.isFinite(meters)) return "";
        if (meters < 1000) return Math.round(meters / 10) * 10 + " m";
        return String.format(Locale.getDefault(), "%.1f km", meters / 1000);
    }

    private void tintMenuLanguageToggle(int accent) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        menuLanguageToggle.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{accent, MUTED}));
        menuLanguageToggle.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{(accent & 0x00FFFFFF) | 0x66000000, (MUTED & 0x00FFFFFF) | 0x44000000}));
    }

    private void showDetail(Restaurant restaurant) {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        home.setVisibility(View.GONE);
        openRestaurantId = restaurant.id;
        showOriginalMenus = false;
        currentMenuBody = null;
        currentMenus = null;
        ++menuRenderVersion;
        int generation = ++detailGeneration;
        ScrollView scroll = new ScrollView(this);
        LinearLayout detail = column();
        detail.setPadding(dp(20), dp(4), dp(20), dp(20));
        scroll.addView(detail);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout heading = row();
        heading.setGravity(Gravity.TOP);
        ImageButton back = toolbarButton(R.drawable.ic_back, getString(R.string.back_restaurants));
        back.setOnClickListener(v -> closeDetail());
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView restaurantName = text(restaurant.name, 24, INK, true);
        restaurantName.setPadding(0, dp(6), 0, 0);
        bindRestaurantName(restaurantName, restaurant, 16);
        heading.addView(restaurantName, new LinearLayout.LayoutParams(0, -2, 1));
        margin(detail, heading, 0, 8);
        TextView contribution = text(Restaurant.euros(restaurant.contribution), 24, restaurantAccent(restaurant), true);
        contribution.setContentDescription(getString(R.string.meal_contribution, Restaurant.euros(restaurant.contribution)));
        LinearLayout priceLine = row();
        priceLine.addView(contribution);
        ImageView hoursIcon = new ImageView(this);
        hoursIcon.setImageResource(R.drawable.ic_hours);
        hoursIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams hoursParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        hoursParams.leftMargin = dp(12);
        priceLine.addView(hoursIcon, hoursParams);
        TextView hoursText = text("", 13, MUTED, false);
        hoursText.setPadding(dp(6), 0, 0, 0);
        priceLine.addView(hoursText, new LinearLayout.LayoutParams(0, -2, 1));
        menuLanguageToggle = new Switch(this);
        menuLanguageToggle.setContentDescription(getString(R.string.menu_translate));
        menuLanguageToggle.setTooltipText(getString(R.string.translation_toggle));
        menuLanguageToggle.setShowText(false);
        menuLanguageToggle.setSwitchMinWidth(dp(40));
        tintMenuLanguageToggle(restaurantAccent(restaurant));
        menuLanguageToggle.setChecked(translationEnabled && !showOriginalMenus);
        menuLanguageToggle.setVisibility(View.GONE);
        menuLanguageToggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled == (translationEnabled && !showOriginalMenus)) return;
            if (enabled && !translationEnabled) {
                translationEnabled = true;
                getPreferences(MODE_PRIVATE).edit().putBoolean("translateMenus", true).apply();
            }
            showOriginalMenus = !enabled;
            if (currentMenuBody != null && currentMenus != null) renderMenus(currentMenuBody, currentMenus);
        });
        priceLine.addView(menuLanguageToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton favorite = toolbarButton(R.drawable.ic_star, getString(R.string.favorite_save));
        bindDetailFavorite(favorite, restaurant);
        priceLine.addView(favorite, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton route = toolbarButton(R.drawable.ic_route, getString(R.string.directions));
        route.setColorFilter(restaurantAccent(restaurant));
        route.setOnClickListener(v -> openUrl("https://www.google.com/maps/search/?api=1&query="
                + Uri.encode(restaurant.name + ", " + restaurant.address + ", " + restaurant.city + ", Slovenia")));
        priceLine.addView(route, new LinearLayout.LayoutParams(dp(48), dp(48)));
        margin(detail, priceLine, 0, 12);
        TextView detailStatus = text(getString(R.string.loading), 12, MUTED, false);
        detail.addView(detailStatus);
        network.execute(() -> {
            try {
                Document document = fetch(restaurant.url);
                String finalHours = OpeningHours.extract(document);
                List<String[]> menus = parseMenus(document);
                List<MenuDay> days = new ArrayList<>();
                Pattern dateArgument = Pattern.compile("GetMenuForDate\\(\\s*\\d+\\s*,\\s*'([^']+)'");
                for (Element link : document.select(".menu-request-link[onclick]")) {
                    Matcher match = dateArgument.matcher(link.attr("onclick"));
                    if (!match.find()) continue;
                    try {
                        days.add(new MenuDay(match.group(1), link.hasClass("disabled")));
                    } catch (DateTimeParseException ignored) {
                        // Ignore a date that is not in the source's expected format.
                    }
                }
                days.sort(Comparator.comparing(day -> day.date));
                onUi(() -> {
                    if (generation != detailGeneration) return;
                    detailStatus.setVisibility(View.GONE);
                    hoursRepository.remember(restaurant.id, finalHours);
                    String hoursSummary = OpeningHours.summary(finalHours, Locale.getDefault());
                    OpeningHours.Status hoursState = OpeningHours.status(finalHours);
                    availability.put(restaurant.id, hoursState);
                    int accent = restaurantAccent(restaurant);
                    contribution.setTextColor(accent);
                    tintMenuLanguageToggle(accent);
                    bindDetailFavorite(favorite, restaurant);
                    route.setColorFilter(accent);
                    hoursText.setTextColor(accent);
                    hoursIcon.setColorFilter(accent);
                    hoursIcon.setContentDescription(hoursSummary);
                    hoursIcon.setTooltipText(hoursSummary);
                    hoursIcon.setVisibility(View.VISIBLE);
                    hoursText.setText(OpeningHours.compactSummary(finalHours, Locale.getDefault()));
                    showMenu(detail, restaurant, days, menus, generation);
                });
            } catch (Exception error) {
                android.util.Log.w("BetterBoni", "Details fetch failed", error);
                onUi(() -> {
                    if (generation == detailGeneration) detailStatus.setText(R.string.details_failed);
                });
            }
        });
    }

    private static final class MenuDay {
        final String requestDate;
        final LocalDate date;
        final boolean embedded;

        MenuDay(String requestDate, boolean embedded) {
            this.requestDate = requestDate;
            this.date = LocalDate.parse(requestDate.replaceAll("\\s+", ""), DateTimeFormatter.ofPattern("d.M.uuuu"));
            this.embedded = embedded;
        }

        @Override public String toString() {
            return date.format(DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", Locale.getDefault()));
        }
    }

    private List<String[]> parseMenus(Document document) {
        return MenuEvidence.parse(document);
    }

    private void rememberMenus(Restaurant restaurant, List<String[]> menus) {
        boolean hasMenu = menus.stream().anyMatch(menu -> !menu[0].trim().isEmpty());
        menuAvailability.put(restaurant.id, hasMenu);
        hoursRepository.rememberMenu(restaurant.id, hasMenu);
        restaurantClassifier.observe(restaurant, menus);
        filter();
    }

    private void showMenu(LinearLayout detail, Restaurant restaurant, List<MenuDay> days,
                          List<String[]> embeddedMenus, int generation) {
        LinearLayout menuBody = column();
        if (days.isEmpty()) {
            detail.addView(menuBody);
            renderMenus(menuBody, embeddedMenus);
            rememberMenus(restaurant, embeddedMenus);
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Ljubljana"));
        MenuDay day = days.get(days.size() - 1);
        for (MenuDay available : days) {
            if (!available.date.isBefore(today)) { day = available; break; }
        }
        if (!day.date.equals(today)) margin(detail, text(getString(R.string.menu_date, day.toString()), 12, MUTED, false), 0, 8);
        detail.addView(menuBody);
        if (day.embedded && !embeddedMenus.isEmpty()) {
            renderMenus(menuBody, embeddedMenus);
            rememberMenus(restaurant, embeddedMenus);
        } else loadMenuDay(restaurant, day, menuBody, generation);
    }

    private void loadMenuDay(Restaurant restaurant, MenuDay day, LinearLayout body, int generation) {
        body.removeAllViews();
        body.addView(text(getString(R.string.menu_loading), 12, MUTED, false));
        network.execute(() -> {
            try {
                Document document = Jsoup.connect(Restaurant.SOURCE + "/sl/restaurant/MenuForDate")
                        .userAgent("BetterBoni/0.1 (Android; unofficial student meal directory)")
                        .timeout(20000).maxBodySize(8 * 1024 * 1024)
                        .data("restaurantId", restaurant.id).data("date", day.requestDate).post();
                List<String[]> menus = parseMenus(document);
                onUi(() -> {
                    if (generation == detailGeneration) {
                        renderMenus(body, menus);
                        rememberMenus(restaurant, menus);
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("BetterBoni", "Menu fetch failed for " + day.requestDate, error);
                onUi(() -> {
                    if (generation != detailGeneration) return;
                    body.removeAllViews();
                    body.addView(text(getString(R.string.menu_failed), 14, MUTED, false));
                    Button retry = button(getString(R.string.retry), false);
                    retry.setOnClickListener(v -> loadMenuDay(restaurant, day, body, generation));
                    body.addView(retry);
                });
            }
        });
    }

    private void renderMenus(LinearLayout body, List<String[]> menus) {
        currentMenuBody = body;
        currentMenus = menus;
        int version = ++menuRenderVersion;
        body.removeAllViews();
        menuLanguageToggle.setVisibility(menus.isEmpty() ? View.GONE : View.VISIBLE);
        if (menus.isEmpty()) {
            ImageView placeholder = new ImageView(this);
            placeholder.setImageResource(R.drawable.no_menu);
            placeholder.setAdjustViewBounds(true);
            placeholder.setScaleType(ImageView.ScaleType.FIT_CENTER);
            placeholder.setContentDescription(getString(R.string.no_menu_image));
            margin(body, placeholder, 8, 8);
            return;
        }
        boolean translate = translationEnabled && !showOriginalMenus;
        menuLanguageToggle.setChecked(translate);
        View attribution = null;
        if (!isLlm()) {
            ImageView google = new ImageView(this);
            google.setImageResource(darkTheme ? R.drawable.translation_attribution_dark : R.drawable.translation_attribution);
            google.setContentDescription(getString(R.string.google_attribution));
            google.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Google Translate")
                    .setMessage(getString(R.string.translation_help))
                    .setPositiveButton(R.string.close, null).show());
            body.addView(google, new LinearLayout.LayoutParams(dp(176), dp(16)));
            attribution = google;
        }
        if (attribution != null) attribution.setVisibility(View.GONE);
        TextView progress = text(getString(R.string.translating), 12, MUTED, false);
        progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.setVisibility(translate ? View.VISIBLE : View.GONE);
        progress.setOnClickListener(v -> showSettings());
        body.addView(progress);
        int[] pending = {0}, failures = {0};
        boolean[] needsWifi = {false};
        String[] firstError = {null};
        for (String[] menu : menus) {
            if (!menu[0].trim().isEmpty()) pending[0]++;
            if (!menu[1].trim().isEmpty()) pending[0]++;
        }
        for (String[] menu : menus) {
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            LinearLayout meal = column();
            meal.setPadding(0, dp(12), 0, dp(12));
            TextView title = text(menu[0], 16, INK, true);
            meal.addView(title);
            meal.setOnLongClickListener(v -> {
                String spokenText = menu[0];
                if (!menu[1].trim().isEmpty()) spokenText += ". " + menu[1].replace(" · ", ", ");
                menuSpeaker.speak(spokenText);
                return true;
            });
            TextView sides = text(menu[1], 13, MUTED, false);
            if (!menu[1].isEmpty()) margin(meal, sides, 3, 0);
            if (translate) {
                MenuTextState titleState = new MenuTextState(menu[0]);
                MenuTextState sidesState = new MenuTextState(menu[1]);
                title.setTag(titleState);
                sides.setTag(sidesState);
                meal.setOnClickListener(v -> {
                    boolean original = !titleState.showOriginal;
                    titleState.showOriginal = original;
                    sidesState.showOriginal = original;
                    title.setText(original ? titleState.original : titleState.translated);
                    sides.setText(original ? sidesState.original : sidesState.translated);
                    meal.setStateDescription(getString(original ? R.string.original : R.string.translation));
                });
            }
            body.addView(meal, new LinearLayout.LayoutParams(-1, -2));
            if (translate) {
                translateMenuText(menu[0], title, body, version, progress, attribution, pending, failures, needsWifi, firstError);
                translateMenuText(menu[1], sides, body, version, progress, attribution, pending, failures, needsWifi, firstError);
            }
        }
    }

    private static final class MenuTextState {
        final String original;
        String translated;
        boolean showOriginal;

        MenuTextState(String original) {
            this.original = original;
            this.translated = original;
        }
    }

    private void translateMenuText(String original, TextView target, LinearLayout body, int version,
                                   TextView progress, View attribution, int[] pending,
                                   int[] failures, boolean[] needsWifi, String[] firstError) {
        if (original.trim().isEmpty()) return;
        menuTranslator.translate(original, allowMobileModels, message -> {
            if (version == menuRenderVersion && !isDestroyed() && failures[0] == 0) progress.setText(message);
        }).addOnCompleteListener(result -> {
            if (version != menuRenderVersion || isDestroyed() || openRestaurantId == null) return;
            if (result.isSuccessful()) {
                if (target.getTag() instanceof MenuTextState textState) {
                    textState.translated = result.getResult();
                    if (!textState.showOriginal) target.setText(textState.translated);
                } else target.setText(result.getResult());
                if (attribution != null) attribution.setVisibility(View.VISIBLE);
            } else {
                failures[0]++;
                if (result.getException() instanceof MenuTranslator.WifiRequiredException) needsWifi[0] = true;
                if (firstError[0] == null) {
                    Exception error = result.getException();
                    firstError[0] = error != null && error.getMessage() != null ? error.getMessage() : getString(R.string.translation_failed);
                }
                progress.setText(needsWifi[0] ? getString(R.string.model_wifi_required) : firstError[0]);
                progress.setTextColor(RED);
                android.util.Log.w("BetterBoni", "Menu translation failed", result.getException());
            }
            if (--pending[0] == 0) {
                if (failures[0] == 0) progress.setVisibility(View.GONE);
                else {
                    progress.setText(needsWifi[0] ? getString(R.string.model_wifi_required) : firstError[0]);
                    progress.setOnClickListener(v -> {
                        if (needsWifi[0] || isLlm()) showSettings();
                        else renderMenus(body, currentMenus);
                    });
                }
            }
        });
    }

    private void closeDetail() {
        menuSpeaker.stop();
        ++detailGeneration;
        ++menuRenderVersion;
        currentMenuBody = null;
        currentMenus = null;
        menuLanguageToggle = null;
        openRestaurantId = null;
        if (root.getChildCount() > 1) root.removeViewAt(1);
        home.setVisibility(View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (settingsPage != null) closeSettings();
        else if (openRestaurantId != null) closeDetail();
        else if (searchOpen) setSearchOpen(false);
        else super.onBackPressed();
    }

    private Document fetch(String url) throws Exception {
        return Jsoup.connect(url).userAgent("BetterBoni/0.1 (Android; unofficial student meal directory)")
                .timeout(20000).maxBodySize(8 * 1024 * 1024).get();
    }

    private void onUi(Runnable action) {
        runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) action.run(); });
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("settingsOpen", settingsPage != null);
        out.putString("query", search.getText().toString());
        out.putBoolean("searchOpen", searchOpen);
        out.putStringArrayList("services", new ArrayList<>(selectedServices));
        out.putInt("minPrice", minPrice);
        out.putInt("maxPrice", maxPrice);
        out.putInt("minRating", minRating);
        out.putDouble("radiusMeters", radiusMeters);
        out.putBoolean("onlyFavorites", onlyFavorites);
        out.putBoolean("showClosed", showClosed);
        out.putBoolean("showWithoutMenu", showWithoutMenu);
        out.putInt("sortMode", sortMode);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        ++menuRenderVersion;
        menuTranslator.close();
        menuSpeaker.close();
        restaurantClassifier.close();
        hoursClock.removeCallbacks(hoursTick);
        hoursClock.removeCallbacks(renderRestaurants);
        hoursRepository.close();
        network.shutdownNow();
        super.onDestroy();
    }

    private class RestaurantAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return visible.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            Restaurant item = visible.get(position);
            LinearLayout outer;
            TextView name, categories, distance, price, hours, address;
            if (recycled == null) {
                outer = column();
                LinearLayout line = row();
                line.setPadding(0, dp(13), 0, dp(13));
                LinearLayout copy = column();
                name = text("", 17, INK, true);
                categories = text("", 12, MUTED, false);
                categories.setSingleLine(true);
                categories.setEllipsize(android.text.TextUtils.TruncateAt.END);
                copy.addView(name);
                address = text("", 12, MUTED, false);
                address.setMaxLines(2);
                address.setEllipsize(android.text.TextUtils.TruncateAt.END);
                margin(copy, address, 3, 0);
                margin(copy, categories, 4, 0);
                line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout numbers = column();
                price = text("", 19, GREEN, true);
                price.setGravity(Gravity.END);
                numbers.addView(price);
                hours = text("", 11, MUTED, false);
                hours.setGravity(Gravity.END);
                hours.setMaxWidth(dp(110));
                hours.setMaxLines(2);
                margin(numbers, hours, 3, 0);
                distance = text("", 12, MUTED, false);
                distance.setGravity(Gravity.END);
                margin(numbers, distance, 4, 0);
                LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(-2, -2);
                priceParams.leftMargin = dp(12);
                line.addView(numbers, priceParams);
                Button favorite = favoriteButton();
                line.addView(favorite, new LinearLayout.LayoutParams(dp(48), dp(48)));
                outer.addView(line, new LinearLayout.LayoutParams(-1, -2));
                outer.setTag(new TextView[]{name, categories, distance, price, favorite, hours, address});
            } else {
                outer = (LinearLayout) recycled;
            }
            TextView[] views = (TextView[]) outer.getTag();
            bindRestaurantName(views[0], item, 14);
            String addressText = item.address.isEmpty() ? item.city
                    : item.address + (item.city.isEmpty() ? "" : ", " + item.city);
            views[6].setText(addressText);
            boolean searching = !Restaurant.searchable(search.getText().toString()).isEmpty();
            views[6].setVisibility(searching && !addressText.trim().isEmpty() ? View.VISIBLE : View.GONE);
            if (classificationEnabled && settingsPage == null && Boolean.TRUE.equals(menuAvailability.get(item.id))) restaurantClassifier.request(item);
            String categoriesText = classificationEnabled
                    ? RestaurantTaxonomy.labels(classifications.getOrDefault(item.id, java.util.Collections.emptyList()), getResources())
                    : item.categorySummary(getResources());
            views[1].setText(categoriesText);
            views[1].setTextColor(MUTED);
            views[1].setVisibility(categoriesText.isEmpty() ? View.GONE : View.VISIBLE);
            String distanceText = distanceLabel(item);
            views[2].setText(distanceText);
            views[2].setVisibility(distanceText.isEmpty() ? View.GONE : View.VISIBLE);
            views[3].setText(Restaurant.euros(item.contribution));
            views[3].setTextColor(restaurantAccent(item));
            String schedule = hoursSchedules.get(item.id);
            views[5].setText(schedule == null ? "" : OpeningHours.compactSummary(schedule, Locale.getDefault()));
            views[5].setContentDescription(schedule == null ? "" : OpeningHours.summary(schedule, Locale.getDefault()));
            views[5].setTextColor(restaurantAccent(item));
            views[5].setVisibility(schedule == null ? View.GONE : View.VISIBLE);
            bindFavorite((Button) views[4], item);
            return outer;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    private ImageButton toolbarButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setColorFilter(GREEN);
        button.setBackgroundResource(R.drawable.button_feedback);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(GREEN);
        if (primary) button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundResource(R.drawable.button_feedback);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinHeight(dp(48));
        return button;
    }
    private void margin(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        parent.addView(child, params);
    }
}
