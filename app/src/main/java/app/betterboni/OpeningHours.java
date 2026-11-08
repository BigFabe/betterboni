package app.betterboni;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpeningHours {
    enum Status { OPEN, CLOSED, UNKNOWN }

    static String extract(org.jsoup.nodes.Document document) {
        org.jsoup.nodes.Element title = document.selectFirst("h4:containsOwn(Delovni čas)");
        if (title == null || title.parent() == null || title.parent().nextElementSibling() == null) return "";
        return title.parent().nextElementSibling().text();
    }

    static Status status(String source) {
        if (source == null || source.isEmpty()) return Status.UNKNOWN;
        String current = summary(source);
        if (current.startsWith("Geöffnet")) return Status.OPEN;
        if (current.startsWith("Öffnet") || current.startsWith("Geschlossen")) return Status.CLOSED;
        return Status.UNKNOWN;
    }

    private static final String DAYS = "Ponedeljek|Torek|Sreda|Četrtek|Petek|Sobota|Nedelja(?:\\s+(?:and|in)\\s+Prazniki)?|Med tednom";
    private static final Pattern DAY = Pattern.compile("(" + DAYS + ")\\s*:\\s*(.*?)(?=(?:" + DAYS + ")\\s*:|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{2})\\s*[-–]\\s*(\\d{1,2})\\s*:\\s*(\\d{2})");

    static String summary(String source) {
        List<List<int[]>> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(null);
        Matcher days = DAY.matcher(source);
        while (days.find()) {
            List<int[]> ranges = new ArrayList<>();
            Matcher times = TIME.matcher(days.group(2));
            while (times.find()) {
                int start = minutes(times.group(1), times.group(2));
                int end = minutes(times.group(3), times.group(4));
                // 00:00–00:00 is also used as a placeholder; do not claim 24-hour opening.
                if (start >= 0 && end >= 0 && start != end) ranges.add(new int[]{start, end});
            }
            String day = Restaurant.searchable(days.group(1));
            List<int[]> schedule = ranges.isEmpty() && !Restaurant.searchable(days.group(2)).contains("zaprto") ? null : ranges;
            if (day.equals("med tednom")) {
                for (int i = 0; i < 5; i++) week.set(i, schedule);
            } else {
                int index = switch (day.split(" ")[0]) {
                    case "ponedeljek" -> 0;
                    case "torek" -> 1;
                    case "sreda" -> 2;
                    case "cetrtek" -> 3;
                    case "petek" -> 4;
                    case "sobota" -> 5;
                    case "nedelja" -> 6;
                    default -> -1;
                };
                if (index >= 0) week.set(index, schedule);
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Ljubljana"));
        LocalDate today = now.toLocalDate();
        LocalDateTime closing = null, nextOpening = null;
        for (int offset = -1; offset <= 7; offset++) {
            LocalDate date = today.plusDays(offset);
            List<int[]> schedule = week.get(date.getDayOfWeek().getValue() - 1);
            if (schedule == null) continue;
            for (int[] range : schedule) {
                LocalDateTime start = date.atStartOfDay().plusMinutes(range[0]);
                LocalDateTime end = date.atStartOfDay().plusMinutes(range[1]);
                if (!end.isAfter(start)) end = end.plusDays(1);
                if (!now.isBefore(start) && now.isBefore(end) && (closing == null || end.isAfter(closing))) closing = end;
                if (start.isAfter(now) && (nextOpening == null || start.isBefore(nextOpening))) nextOpening = start;
            }
        }
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm");
        if (closing != null) return "Geöffnet bis " + closing.format(clock);
        if (week.get(today.getDayOfWeek().getValue() - 1) == null) return "Öffnungszeiten unbekannt";
        if (nextOpening == null) return "Geschlossen · nächste Öffnung unbekannt";
        String when = nextOpening.toLocalDate().equals(today) ? "heute"
                : nextOpening.toLocalDate().equals(today.plusDays(1)) ? "morgen"
                : "am " + nextOpening.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.GERMAN);
        return "Öffnet " + when + " um " + nextOpening.format(clock);
    }

    static String summary(String source, Locale locale) {
        String result = summary(source);
        if (locale.getLanguage().equals("de")) return result;
        if (result.equals("Öffnungszeiten unbekannt")) return "Opening hours unknown";
        if (result.equals("Geschlossen · nächste Öffnung unbekannt")) return "Closed · next opening unknown";
        result = result.replace("Geöffnet bis ", "Open until ").replace("Öffnet ", "Opens ")
                .replace("heute", "today").replace("morgen", "tomorrow").replace("am ", "on ").replace(" um ", " at ");
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            result = result.replace(day.getDisplayName(TextStyle.FULL, Locale.GERMAN), day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        }
        return result;
    }

    static String compactSummary(String source, Locale locale) {
        if (locale.getLanguage().equals("de")) return compactSummary(source);
        return summary(source, locale).replace("Open ", "").replace("Opens ", "")
                .replace(" at ", " ").replace("on ", "");
    }

    static String compactSummary(String source) {
        return summary(source).replace("Geöffnet ", "").replace("Öffnet ", "")
                .replace(" um ", " ").replace("am ", "");
    }

    private static int minutes(String hours, String minutes) {
        int h = Integer.parseInt(hours), m = Integer.parseInt(minutes);
        return h <= 24 && m < 60 && (h < 24 || m == 0) ? h * 60 + m : -1;
    }
}
