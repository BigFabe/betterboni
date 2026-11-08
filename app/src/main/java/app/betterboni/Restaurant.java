package app.betterboni;

import android.content.res.Resources;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;

final class Restaurant {
    static final String SOURCE = "https://www.studentska-prehrana.si";
    final String id, name, address, city, url, latitude, longitude;
    final int contribution, fullPrice;
    final boolean vegetarian, weekend, delivery;
    final Set<String> services = new HashSet<>();
    final int rating;

    Restaurant(Element row) {
        id = row.attr("data-posid");
        Element heading = row.selectFirst("h2 a");
        name = heading == null ? row.attr("data-lokal") : heading.text();
        address = row.attr("data-naslov");
        city = row.attr("data-city");
        url = SOURCE + row.attr("data-detailslink");
        latitude = row.attr("data-lat");
        longitude = row.attr("data-lon");
        contribution = cents(row.attr("data-doplacilo"));
        fullPrice = cents(row.attr("data-cena"));
        for (String className : row.classNames()) {
            if (className.startsWith("service-")) services.add(className.substring(8));
        }
        vegetarian = services.contains("1");
        weekend = services.contains("20");
        delivery = services.contains("5");
        Element stars = row.selectFirst(".acidjs-rating-stars input[checked]");
        int parsedRating = 0;
        if (stars != null) {
            try { parsedRating = Integer.parseInt(stars.attr("value")); }
            catch (NumberFormatException ignored) { /* No usable rating. */ }
        }
        rating = parsedRating >= 1 && parsedRating <= 5 ? parsedRating : 0;
    }

    static List<Restaurant> parse(Document document) {
        List<Restaurant> result = new ArrayList<>();
        for (Element row : document.select(".restaurant-row[data-posid][data-detailslink]")) {
            try {
                Restaurant item = new Restaurant(row);
                if (!item.name.trim().isEmpty() && row.attr("data-detailslink").startsWith("/sl/restaurant/Details/")) {
                    result.add(item);
                }
            } catch (NumberFormatException | ArithmeticException ignored) {
                // A malformed price must not appear as a free meal.
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("No restaurant data found.");
        return result;
    }

    static Map<String, String> filterOptions(Document document, Resources resources) {
        Map<String, String> options = new LinkedHashMap<>();
        for (Element input : document.select(".restaurant-serivce-checkbox[data-service]")) {
            String id = input.attr("data-service");
            if (id.equals("0") || id.isEmpty() || input.parent() == null) continue;
            int labelId = switch (id) {
                case "1" -> R.string.vegetarian;
                case "5" -> R.string.delivery;
                case "7" -> R.string.salad_bar;
                case "8" -> R.string.accessible;
                case "9" -> R.string.student_offers;
                case "10" -> R.string.gluten_free_options;
                case "12" -> R.string.healthy;
                case "13" -> R.string.public_institution;
                case "20" -> R.string.weekend_open;
                case "21" -> R.string.lunch;
                case "22" -> R.string.pizza;
                case "23" -> R.string.fast_food;
                case "69" -> R.string.new_restaurant;
                default -> 0;
            };
            String label = labelId == 0 ? input.parent().text() : resources.getString(labelId);
            options.put(id, label);
        }
        return options;
    }

    static String searchable(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    static String euros(int cents) {
        return String.format(Locale.getDefault(), Locale.getDefault().getLanguage().equals("de") ? "%.2f €" : "€%.2f", cents / 100.0);
    }

    private static int cents(String value) {
        return new BigDecimal(value.replace(',', '.').trim()).movePointRight(2).intValueExact();
    }

    double distanceFrom(android.location.Location origin) {
        if (origin == null) return Double.POSITIVE_INFINITY;
        try {
            double lat = Double.parseDouble(latitude), lon = Double.parseDouble(longitude);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                    || (lat == 0 && lon == 0)) return Double.POSITIVE_INFINITY;
            float[] result = new float[1];
            android.location.Location.distanceBetween(origin.getLatitude(), origin.getLongitude(), lat, lon, result);
            return result[0];
        } catch (NumberFormatException error) {
            return Double.POSITIVE_INFINITY;
        }
    }

    String categorySummary(Resources resources) {
        // Prefer useful food categories; never infer them from the restaurant's name.
        String[] priority = {"12", "22", "23", "7", "1", "5", "10", "8", "20"};
        int[] names = {R.string.healthy, R.string.pizza, R.string.fast_food, R.string.salad_bar,
                R.string.veg_short, R.string.delivery, R.string.gluten_free_short, R.string.accessible, R.string.weekend_short};
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < priority.length; i++) {
            if (services.contains(priority[i])) labels.add(resources.getString(names[i]));
            if (labels.size() == 3) break;
        }
        return String.join(" · ", labels);
    }
}
