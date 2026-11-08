package app.betterboni;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MenuEvidence {
    final JSONArray dishes;
    final String hash;

    MenuEvidence(List<String[]> menus) throws Exception {
        TreeMap<String, String[]> distinct = new TreeMap<>();
        for (String[] menu : menus) {
            if (!menu[0].trim().isEmpty()) distinct.putIfAbsent(normalize(menu[0]), menu);
        }
        dishes = new JSONArray();
        StringBuilder canonical = new StringBuilder();
        for (String[] menu : distinct.values()) {
            dishes.put(new JSONObject().put("title", menu[0]).put("sides", menu[1]));
            canonical.append(normalize(menu[0])).append('\t').append(normalize(menu[1])).append('\n');
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        hash = hex.toString();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    static MenuEvidence fromJson(String json) throws Exception {
        JSONArray dishes = new JSONArray(json);
        List<String[]> menus = new ArrayList<>();
        for (int i = 0; i < dishes.length(); i++) {
            JSONObject dish = dishes.getJSONObject(i);
            menus.add(new String[]{dish.getString("title"), dish.optString("sides")});
        }
        return new MenuEvidence(menus);
    }

    static List<String[]> parse(Document document) {
        Element container = document.getElementById("menu-list");
        if (container == null) container = document.body();
        List<String[]> menus = new ArrayList<>();
        for (Element entry : container.select(".shadow-wrapper")) {
            Element title = entry.selectFirst("h5");
            if (title == null) continue;
            List<String> sides = new ArrayList<>();
            for (Element side : entry.select("li")) if (!side.text().trim().isEmpty()) sides.add(side.text());
            menus.add(new String[]{title.text().replaceFirst("^\\d+\\s+", ""), String.join(" · ", sides)});
        }
        return menus;
    }

    static MenuEvidence fetch(Restaurant restaurant) throws Exception {
        Document document = Jsoup.connect(restaurant.url).userAgent("BetterBoni/0.1 (menu classification)")
                .timeout(20000).maxBodySize(8 * 1024 * 1024).get();
        return fetch(restaurant, document);
    }

    static MenuEvidence fetch(Restaurant restaurant, Document document) throws Exception {
        List<String[]> menus = parse(document);
        if (!menus.isEmpty()) return new MenuEvidence(menus);
        Pattern argument = Pattern.compile("GetMenuForDate\\(\\s*\\d+\\s*,\\s*'([^']+)'");
        TreeMap<LocalDate, String> dates = new TreeMap<>();
        for (Element link : document.select(".menu-request-link[onclick]")) {
            Matcher match = argument.matcher(link.attr("onclick"));
            if (!match.find()) continue;
            try {
                dates.put(LocalDate.parse(match.group(1).replaceAll("\\s+", ""), DateTimeFormatter.ofPattern("d.M.uuuu")), match.group(1));
            } catch (java.time.format.DateTimeParseException ignored) { /* Only request dates offered by the source. */ }
        }
        if (!dates.isEmpty()) {
            LocalDate today = LocalDate.now(ZoneId.of("Europe/Ljubljana"));
            LocalDate date = dates.ceilingKey(today);
            if (date == null) date = dates.lastKey();
            Document fragment = Jsoup.connect(Restaurant.SOURCE + "/sl/restaurant/MenuForDate")
                    .userAgent("BetterBoni/0.1 (menu classification)").timeout(20000).maxBodySize(8 * 1024 * 1024)
                    .data("restaurantId", restaurant.id).data("date", dates.get(date)).post();
            menus = parse(fragment);
        }
        return new MenuEvidence(menus);
    }
}
