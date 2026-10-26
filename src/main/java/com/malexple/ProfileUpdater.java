package com.malexple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileUpdater {
    private static final String USERNAME = "malexple";
    private static final String TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String GEMINI_KEY = System.getenv("GEMINI_API_KEY");
    private static final String GEMINI_MODEL = envOr("GEMINI_MODEL", "gemini-3.5-flash-lite");
    private static final String GEMINI_FALLBACK = "gemini-3.8-flash";
    private static final String PROMPT_VERSION = "v4";
    private static final int MAX_REFRESHES = 15;
    private static final int README_LIMIT = 12_000;
    private static final int ROADMAP_LIMIT = 6_000;
    private static final int MAX_COMMIT_PAGES = 30;
    private static final int MONTHS_SHOWN = 12;
    private static final int ACTIVE_DAYS = 30;
    private static final int QUIET_DAYS = 90;
    private static final String STATS_KEY = "__stats__";
    private static final String GH_JSON = "application/vnd.github.v3+json";
    private static final Path CACHE_PATH = Paths.get("ai-descriptions.json");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"));
    private static final DateTimeFormatter DOT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final double BASE_COMMITS = 0.5;
    private static final double BASE_LINES = 150.0;
    private static final double W_COMMIT = 0.4;
    private static final double W_PLAN = 0.3;
    private static final double W_INTENSITY = 0.3;

    private static final Yaml yaml = new Yaml();

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "<!--\\s*PROJECTS_TABLE_START:\\s*(.+?)\\s*-->(.*?)<!--\\s*PROJECTS_TABLE_END\\s*-->",
            Pattern.DOTALL
    );
    private static final Pattern STATS_PATTERN = Pattern.compile(
            "<!--\\s*STATS_START\\s*-->(.*?)<!--\\s*STATS_END\\s*-->",
            Pattern.DOTALL
    );

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        System.out.println("🔍 Загружаю список репозиториев...");

        HttpResponse<String> response = client.send(
                githubRequest("https://api.github.com/users/" + USERNAME + "/repos?per_page=100&sort=updated", GH_JSON),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Ошибка API: " + response.body());
            System.exit(1);
        }

        JSONObject cache = loadCache();
        String cacheBefore = cache.toString();
        int[] budget = {MAX_REFRESHES};
        if (GEMINI_KEY == null || GEMINI_KEY.isBlank()) {
            System.out.println("ℹ GEMINI_API_KEY не задан — карточки не обновляются");
        }

        JSONArray repos = new JSONArray(response.body());
        Map<String, List<ProjectInfo>> categorized = new LinkedHashMap<>();
        List<RepoRow> rows = new ArrayList<>();
        List<String> processed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (int i = 0; i < repos.length(); i++) {
            JSONObject repo = repos.getJSONObject(i);
            String repoName = repo.getString("name");

            if (repoName.equals(USERNAME) || repo.optBoolean("fork") || repo.optBoolean("private")) {
                continue;
            }

            String url = repo.getString("html_url");
            String pushedAt = repo.optString("pushed_at", "");
            String createdAt = repo.optString("created_at", "");

            Stat stat = resolveStat(client, repoName, pushedAt, cache);
            var meta = fetchMetadata(client, repoName);

            String title = repoName;
            String category = null;
            Enriched e = null;

            if (meta != null && meta.category() != null && !meta.category().isEmpty()) {
                title = meta.title() != null && !meta.title().isEmpty() ? meta.title() : repoName;
                category = meta.category();
                String desc = meta.description() != null ? meta.description() : "";
                e = resolve(client, repoName, meta, pushedAt, cache, budget, stat);

                categorized.computeIfAbsent(category, k -> new ArrayList<>())
                        .add(new ProjectInfo(title, desc, e.card(), url, e.facts(), e.plan()));
                processed.add(repoName);
                System.out.println("✓ " + repoName + " → " + category);
            } else {
                skipped.add(repoName);
            }

            rows.add(new RepoRow(title, url, category, createdAt, pushedAt, stat,
                    e == null ? null : e.facts(), e == null ? null : e.plan()));
        }

        System.out.println("\n📊 Обработано: " + processed.size() + " проектов");
        System.out.println("📊 Пропущено (нет метаданных): " + skipped.size() + " проектов");
        System.out.println("📊 Категорий: " + categorized.size());

        saveCache(cache, cacheBefore);

        Path readmePath = Paths.get("README.md");
        if (!Files.exists(readmePath)) {
            System.err.println("README.md не найден!");
            System.exit(1);
        }

        String readme = Files.readString(readmePath, StandardCharsets.UTF_8);
        Matcher matcher = CATEGORY_PATTERN.matcher(readme);
        Map<String, String> replacements = new LinkedHashMap<>();
        Set<String> markers = new HashSet<>();

        while (matcher.find()) {
            String section = matcher.group(1).trim();
            markers.add(section);
            List<ProjectInfo> projects = categorized.getOrDefault(section, Collections.emptyList());

            String replacement = "<!-- PROJECTS_TABLE_START: " + section + " -->\n\n"
                    + generateCards(projects)
                    + "\n\n<!-- PROJECTS_TABLE_END -->";

            replacements.put(matcher.group(0), replacement);
            System.out.println("✓ Раздел '" + section + "': " + projects.size() + " проектов");
        }

        for (String category : categorized.keySet()) {
            if (!markers.contains(category)) {
                System.out.println("⚠ Нет маркера в README для категории: '" + category + "'");
            }
        }

        StringBuilder updated = new StringBuilder(readme);
        for (var entry : replacements.entrySet()) {
            int idx = updated.indexOf(entry.getKey());
            if (idx != -1) {
                updated.replace(idx, idx + entry.getKey().length(), entry.getValue());
            }
        }

        String result = updated.toString();
        Matcher sm = STATS_PATTERN.matcher(result);
        if (sm.find()) {
            String block = "<!-- STATS_START -->\n\n" + generateStats(rows) + "\n\n<!-- STATS_END -->";
            result = result.substring(0, sm.start()) + block + result.substring(sm.end());
        } else {
            System.out.println("ℹ В README нет маркеров STATS_START / STATS_END");
        }

        Files.writeString(readmePath, result, StandardCharsets.UTF_8);
        System.out.println("\n✅ README обновлён!");

        if (!skipped.isEmpty()) {
            System.out.println("\n⚠️  Проекты без metadataInfo.yaml:");
            skipped.forEach(s -> System.out.println("   - " + s));
        }
    }

    // ---------- GitHub ----------

    private static HttpRequest githubRequest(String url, String accept) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "token " + TOKEN)
                .header("Accept", accept)
                .header("User-Agent", "ProfileUpdater/1.0")
                .build();
    }

    private static MetaInfo fetchMetadata(HttpClient client, String repoName) {
        try {
            HttpResponse<String> resp = client.send(
                    githubRequest("https://api.github.com/repos/" + USERNAME + "/" + repoName
                            + "/contents/metadataInfo.yaml", GH_JSON),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                if (resp.statusCode() != 404) {
                    System.err.println("⚠ " + repoName + ": HTTP " + resp.statusCode());
                }
                return null;
            }

            JSONObject obj = new JSONObject(resp.body());
            String b64 = obj.getString("content").replaceAll("\\s", "");
            String content = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);

            Map<String, Object> map = yaml.load(content);
            if (map == null) {
                return null;
            }
            return new MetaInfo(str(map, "title"), str(map, "description"), str(map, "category"));
        } catch (Exception e) {
            System.err.println("⚠ Ошибка чтения " + repoName + "/metadataInfo.yaml: " + e.getMessage());
            return null;
        }
    }

    private static String fetchRaw(HttpClient client, String url, int limit) throws Exception {
        HttpResponse<String> resp = client.send(githubRequest(url, "application/vnd.github.raw+json"),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return null;
        }
        String body = resp.body();
        return body.length() > limit ? body.substring(0, limit) : body;
    }

    private static String commitDate(JSONObject commit) {
        return commit.getJSONObject("commit").getJSONObject("author").getString("date");
    }

    // Вся история коммитов (до MAX_COMMIT_PAGES страниц) + топ-3 языка
    private static Stat fetchStat(HttpClient client, String repo) throws Exception {
        String base = "https://api.github.com/repos/" + USERNAME + "/" + repo;
        Map<String, Integer> months = new TreeMap<>();
        int commits = 0;
        String first = null;
        String last = null;

        for (int page = 1; page <= MAX_COMMIT_PAGES; page++) {
            HttpResponse<String> r = client.send(
                    githubRequest(base + "/commits?per_page=100&page=" + page, GH_JSON),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 409 && page == 1) {
                break; // пустой репозиторий
            }
            if (r.statusCode() != 200) {
                return null;
            }
            JSONArray arr = new JSONArray(r.body());
            for (int i = 0; i < arr.length(); i++) {
                String date = commitDate(arr.getJSONObject(i));
                if (last == null) last = date;
                first = date;
                months.merge(YearMonth.from(Instant.parse(date).atZone(MSK)).toString(), 1, Integer::sum);
            }
            commits += arr.length();
            if (arr.length() < 100) break;
        }

        List<String> langs = new ArrayList<>();
        HttpResponse<String> lr = client.send(githubRequest(base + "/languages", GH_JSON),
                HttpResponse.BodyHandlers.ofString());
        if (lr.statusCode() == 200) {
            JSONObject lo = new JSONObject(lr.body());
            langs = new ArrayList<>(lo.keySet().stream()
                    .sorted((a, b) -> Long.compare(lo.getLong(b), lo.getLong(a)))
                    .limit(3).toList());
        }
        return new Stat(commits, first, last, langs, months);
    }

    // GitHub считает статистику лениво: сначала 202, через пару секунд 200
    private static long fetchAdditions(HttpClient client, String base) throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = client.send(githubRequest(base + "/stats/code_frequency", GH_JSON),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JSONArray weeks = new JSONArray(r.body());
                long sum = 0;
                for (int k = 0; k < weeks.length(); k++) {
                    sum += weeks.getJSONArray(k).getLong(1);
                }
                return sum;
            }
            if (r.statusCode() != 202) {
                return -1;
            }
            Thread.sleep(3000);
        }
        return -1;
    }

    // ---------- Статистика репозитория: кэш по pushed_at ----------

    private static Stat resolveStat(HttpClient client, String repo, String pushedAt, JSONObject cache) {
        JSONObject all = cache.optJSONObject(STATS_KEY);
        if (all == null) {
            all = new JSONObject();
            cache.put(STATS_KEY, all);
        }
        JSONObject c = all.optJSONObject(repo);
        if (c != null && pushedAt.equals(c.optString("stamp"))) {
            return statFromCache(c);
        }
        try {
            Stat s = fetchStat(client, repo);
            if (s != null) {
                all.put(repo, statToCache(pushedAt, s));
                return s;
            }
        } catch (Exception e) {
            System.err.println("⚠ Статистика " + repo + ": " + e.getMessage());
        }
        return c != null ? statFromCache(c) : null;
    }

    private static JSONObject statToCache(String stamp, Stat s) {
        return new JSONObject()
                .put("stamp", stamp)
                .put("commits", s.commits())
                .put("first", s.first() == null ? "" : s.first())
                .put("last", s.last() == null ? "" : s.last())
                .put("langs", new JSONArray(s.langs()))
                .put("months", new JSONObject(s.months()));
    }

    private static Stat statFromCache(JSONObject c) {
        Map<String, Integer> months = new TreeMap<>();
        JSONObject mo = c.optJSONObject("months");
        if (mo != null) {
            for (String k : mo.keySet()) {
                months.put(k, mo.getInt(k));
            }
        }
        List<String> langs = new ArrayList<>();
        JSONArray la = c.optJSONArray("langs");
        if (la != null) {
            for (int i = 0; i < la.length(); i++) {
                langs.add(la.getString(i));
            }
        }
        String first = c.optString("first", "");
        String last = c.optString("last", "");
        return new Stat(c.optInt("commits", 0), first.isEmpty() ? null : first,
                last.isEmpty() ? null : last, langs, months);
    }

    // ---------- Анализ: кэш > Gemini ----------

    private static Enriched resolve(HttpClient client, String repo, MetaInfo meta, String pushedAt,
                                    JSONObject cache, int[] budget, Stat stat) {
        JSONObject cached = cache.optJSONObject(repo);
        Enriched old = cached == null ? new Enriched(null, null, null) : fromCache(cached);

        if (GEMINI_KEY == null || GEMINI_KEY.isBlank()) {
            return old;
        }

        String stamp = PROMPT_VERSION + "|" + GEMINI_MODEL + "|" + pushedAt;
        if (cached != null && stamp.equals(cached.optString("stamp"))) {
            return old;
        }
        if (cached != null) {
            if (budget[0] <= 0) {
                System.out.println("ℹ " + repo + ": лимит обновлений за запуск исчерпан");
                return old;
            }
            budget[0]--;
        }

        try {
            if (stat == null || stat.commits() == 0) {
                return old;
            }
            String readme = fetchRaw(client, "https://api.github.com/repos/" + USERNAME + "/" + repo
                    + "/readme", README_LIMIT);
            if (readme == null || readme.isBlank()) {
                return old;
            }
            String roadmap = fetchRaw(client, "https://api.github.com/repos/" + USERNAME + "/" + repo
                    + "/contents/ROADMAP.md", ROADMAP_LIMIT);

            Analysis a = askGemini(client, repo, meta, readme, roadmap);
            if (a == null) {
                return old;
            }
            Facts facts = new Facts(stat.commits(), stat.first(), stat.last(),
                    fetchAdditions(client, "https://api.github.com/repos/" + USERNAME + "/" + repo));
            Enriched fresh = new Enriched(a.card(), facts, a.plan());
            cache.put(repo, toCache(stamp, fresh));
            System.out.println("🤖 " + repo + ": проанализирован");
            return fresh;
        } catch (Exception e) {
            System.err.println("⚠ Анализ " + repo + ": " + e.getMessage());
            return old;
        }
    }

    private static Analysis askGemini(HttpClient client, String repoName, MetaInfo meta,
                                      String readme, String roadmap) throws Exception {
        String prompt = "Ты технический аналитик. Подготовь карточку проекта для витрины GitHub-профиля "
                + "и верни ТОЛЬКО JSON такого вида:\n"
                + "{\"emoji\": string, \"what_it_does\": string, \"pain\": string, "
                + "\"ratings\": {"
                + "\"science\": {\"score\": int, \"comment\": string}, "
                + "\"tech\": {\"score\": int, \"comment\": string}, "
                + "\"originality\": {\"score\": int, \"comment\": string}, "
                + "\"ambition\": {\"score\": int, \"comment\": string}}, "
                + "\"foundations\": string, \"feature\": string, "
                + "\"plan\": null | {\"total_stages\": int, \"completed_stages\": int, "
                + "\"current_stage\": string, \"planned_duration_days\": int | null}}\n\n"
                + "Правила текста: русский язык, простой текст без markdown, без символа |.\n"
                + "emoji: ровно один эмодзи по теме проекта.\n"
                + "what_it_does: 2-3 предложения, до 350 символов: что проект делает и как устроен.\n"
                + "pain: 1-2 предложения, до 250 символов: какую проблему решает и чем отличается от обычных решений.\n"
                + "foundations: ключевые научные или технические основы: названия моделей, алгоритмов, "
                + "стандартов, технологий, упомянутых в тексте. Перечисли через запятую.\n"
                + "feature: одно предложение: принципиальная особенность или отказ от привычного подхода.\n"
                + "comment: до 70 символов, поясняет оценку.\n\n"
                + "Оценки 1-10, будь строгим, большинство проектов получает 4-7:\n"
                + "science: научная сложность предметной области (1-3 обычная прикладная, 4-6 требует специальных знаний, "
                + "7-8 опирается на научные модели, 9-10 открытая научная проблема).\n"
                + "tech: техническая сложность реализации (архитектура, объём, число модулей, нетривиальные алгоритмы).\n"
                + "originality: новизна подхода по тексту. Не утверждай, что аналогов нет, если автор этого не написал.\n"
                + "ambition: масштаб замысла.\n"
                + "Оценивай по тому, что реально описано и реализовано, а не по обещаниям.\n\n"
                + "plan: заполняй только если в README или ROADMAP есть явный список этапов, фаз или чекбоксов. "
                + "completed_stages: этапы, отмеченные выполненными. current_stage: короткое название текущего этапа. "
                + "planned_duration_days: только если срок указан явно (недели переведи в дни), иначе null. "
                + "Если плана нет, plan: null.\n"
                + "Опирайся только на факты из текста, ничего не выдумывай.\n\n"
                + "Проект: " + repoName + "\n"
                + "Краткое описание: " + (meta.description() == null ? "" : meta.description()) + "\n\n"
                + "README:\n" + readme + "\n\n"
                + "ROADMAP:\n" + (roadmap == null ? "(нет)" : roadmap);

        for (String model : new String[]{GEMINI_MODEL, GEMINI_FALLBACK}) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                HttpResponse<String> resp = sendGemini(client, model, prompt);
                int code = resp.statusCode();
                if (code == 200) {
                    String text = new JSONObject(resp.body())
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                            .getString("text").trim();
                    return parseAnalysis(text);
                }
                String b = resp.body();
                System.err.println("⚠ Gemini " + model + " HTTP " + code + ": "
                        + (b.length() > 160 ? b.substring(0, 160) : b));
                if (code != 503 && code != 429) break;
                Thread.sleep(attempt * 5000L);
            }
        }
        return null;
    }

    private static HttpResponse<String> sendGemini(HttpClient client, String model, String prompt)
            throws Exception {
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("responseMimeType", "application/json"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("x-goog-api-key", GEMINI_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Analysis parseAnalysis(String text) {
        String clean = text.replaceAll("^```(?:json)?\\s*|\\s*```$", "");
        JSONObject j = new JSONObject(clean);

        Plan plan = null;
        JSONObject p = j.optJSONObject("plan");
        if (p != null) {
            int total = p.optInt("total_stages", 0);
            if (total > 0) {
                int completed = Math.max(0, Math.min(total, p.optInt("completed_stages", 0)));
                int days = p.optInt("planned_duration_days", 0);
                plan = new Plan(total, completed, p.optString("current_stage", "").trim(),
                        days > 0 ? days : null);
            }
        }
        j.remove("plan");
        return new Analysis(j, plan);
    }

    // ---------- Кэш ----------

    private static JSONObject toCache(String stamp, Enriched e) {
        JSONObject o = new JSONObject().put("stamp", stamp);
        if (e.card() != null) {
            o.put("card", e.card());
        }
        Facts f = e.facts();
        if (f != null) {
            o.put("facts", new JSONObject().put("commits", f.commits()).put("first", f.first())
                    .put("last", f.last()).put("additions", f.additions()));
        }
        Plan p = e.plan();
        if (p != null) {
            JSONObject po = new JSONObject().put("total", p.total()).put("completed", p.completed())
                    .put("current", p.current());
            if (p.plannedDays() != null) {
                po.put("plannedDays", p.plannedDays().intValue());
            }
            o.put("plan", po);
        }
        return o;
    }

    private static Enriched fromCache(JSONObject c) {
        JSONObject fo = c.optJSONObject("facts");
        Facts f = fo == null ? null
                : new Facts(fo.getInt("commits"), fo.getString("first"), fo.getString("last"),
                fo.optLong("additions", -1));
        JSONObject po = c.optJSONObject("plan");
        Plan p = po == null ? null
                : new Plan(po.getInt("total"), po.getInt("completed"), po.optString("current", ""),
                po.has("plannedDays") ? Integer.valueOf(po.getInt("plannedDays")) : null);
        return new Enriched(c.optJSONObject("card"), f, p);
    }

    private static JSONObject loadCache() {
        try {
            if (Files.exists(CACHE_PATH)) {
                return new JSONObject(Files.readString(CACHE_PATH, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.err.println("⚠ Не удалось прочитать кэш: " + e.getMessage());
        }
        return new JSONObject();
    }

    private static void saveCache(JSONObject cache, String before) throws Exception {
        if (cache.toString().equals(before)) {
            return;
        }
        String json = new JSONObject(new TreeMap<>(cache.toMap())).toString(2);
        Files.writeString(CACHE_PATH, json + "\n", StandardCharsets.UTF_8);
    }

    // ---------- Карточки ----------

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static String generateCards(List<ProjectInfo> projects) {
        if (projects.isEmpty()) return "*Нет проектов в этой категории*";
        List<String> cards = new ArrayList<>();
        for (var p : projects) {
            cards.add(renderCard(p));
        }
        return String.join("\n\n---\n\n", cards);
    }

    private static String renderCard(ProjectInfo p) {
        JSONObject c = p.card();
        var sb = new StringBuilder();

        String emoji = c == null ? "" : c.optString("emoji", "").trim();
        if (emoji.isEmpty() || emoji.length() > 8) emoji = "📦";
        sb.append("### ").append(emoji).append(" ").append(p.title()).append("\n\n");

        if (!p.description().isEmpty()) {
            sb.append("**").append(cell(p.description())).append("**\n\n");
        }
        if (c == null) {
            return sb.append("[→ Смотреть код на GitHub](").append(p.url()).append(")").toString();
        }

        field(sb, "Что делает", c.optString("what_it_does", ""));
        field(sb, "Какую боль закрывает", c.optString("pain", ""));

        JSONObject r = c.optJSONObject("ratings");
        if (r != null) {
            sb.append("#### 📊 Оценки проекта\n\n");
            sb.append("| Показатель | Значение | Комментарий |\n");
            sb.append("|------------|----------|-------------|\n");
            ratingRow(sb, "🔬 Научная сложность", r.optJSONObject("science"));
            ratingRow(sb, "🛠 Техническая сложность", r.optJSONObject("tech"));
            ratingRow(sb, "💡 Оригинальность", r.optJSONObject("originality"));
            ratingRow(sb, "🎯 Амбициозность", r.optJSONObject("ambition"));
            sb.append("\n<sub>Оценки сгенерированы ИИ по README и ROADMAP проекта</sub>\n\n");
        }

        if (p.facts() != null) {
            renderSpeed(sb, p.facts(), p.plan());
        }

        field(sb, "Ключевые основы", c.optString("foundations", ""));
        field(sb, "Особенность", c.optString("feature", ""));
        sb.append("[→ Смотреть код на GitHub](").append(p.url()).append(")");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String label, String text) {
        String t = cell(text == null ? "" : text);
        if (!t.isEmpty()) {
            sb.append("**").append(label).append(":** ").append(t).append("\n\n");
        }
    }

    private static void ratingRow(StringBuilder sb, String label, JSONObject o) {
        if (o == null || o.optInt("score", 0) <= 0) return;
        int score = Math.max(1, Math.min(10, o.optInt("score", 0)));
        sb.append("| ").append(label).append(" | ").append(score).append("/10 | ")
                .append(cell(o.optString("comment", ""))).append(" |\n");
    }

    private static void renderSpeed(StringBuilder sb, Facts f, Plan plan) {
        Metrics m = Metrics.of(f, plan);
        sb.append("#### ⚡ Скорость работы\n\n");
        sb.append("| Метрика | Значение | Статус |\n");
        sb.append("|---------|----------|--------|\n");

        sb.append("| 🔄 Коммитов в день | ")
                .append(String.format(Locale.ROOT, "%.1f", m.commitsPerDay())).append(" | ")
                .append(status(m.commitSpeed())).append(" ")
                .append(String.format(Locale.ROOT, "%.1fx", m.commitSpeed()))
                .append(m.commitSpeed() >= 1 ? " быстрее среднего" : " от среднего").append(" |\n");

        if (plan != null) {
            sb.append("| 📅 Выполнение плана | ").append(m.planPercent()).append("% | ");
            if (m.planSpeed() != null) {
                sb.append(status(m.planSpeed())).append(" ")
                        .append(String.format(Locale.ROOT, "%.2fx от графика", m.planSpeed()));
            } else {
                sb.append("—");
            }
            sb.append(" |\n");
        }

        if (m.linesPerDay() != null) {
            sb.append("| 💻 Строк кода в день | ")
                    .append(String.format(Locale.ROOT, "%.0f", m.linesPerDay())).append(" | ")
                    .append(status(m.intensity())).append(" ")
                    .append(String.format(Locale.ROOT, "%.1fx", m.intensity()))
                    .append(m.intensity() >= 1 ? " выше среднего" : " от среднего").append(" |\n");
        }

        sb.append("| **⚡ Общий коэффициент** | ")
                .append(String.format(Locale.ROOT, "**%.2fx**", m.overall())).append(" | **")
                .append(label(m.overall())).append("** |\n\n");

        sb.append("**Период разработки:** ").append(m.days()).append(" дн. (")
                .append(RU_DATE.format(day(f.first()))).append(" — ")
                .append(RU_DATE.format(day(f.last()))).append(")  \n");
        if (plan != null) {
            if (!plan.current().isEmpty()) {
                sb.append("**Текущий этап:** ").append(cell(plan.current())).append("  \n");
            }
            sb.append("**Прогресс:** ").append(plan.completed()).append("/").append(plan.total())
                    .append(" этапов завершено  \n");
        }
        sb.append("\n");
    }

    private static String status(double v) {
        if (v < 0.5) return "🔴";
        if (v < 1.0) return "🟡";
        if (v < 3.0) return "🟢";
        if (v < 10.0) return "🟢🟢";
        return "🟢🟢🟢";
    }

    private static String label(double v) {
        String name = v < 0.5 ? "низкая" : v < 1.0 ? "средняя" : v < 3.0 ? "высокая"
                : v < 10.0 ? "очень высокая" : "феноменальная";
        return status(v) + " " + name;
    }

    private static LocalDate day(String iso) {
        return LocalDate.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
    }

    // ---------- Статистика ----------

    private static LocalDate dayMsk(String iso) {
        return Instant.parse(iso).atZone(MSK).toLocalDate();
    }

    private static String dot(String iso) {
        return iso == null || iso.isEmpty() ? "—" : DOT_DATE.format(dayMsk(iso));
    }

    private static String activity(String pushedIso) {
        if (pushedIso == null || pushedIso.isEmpty()) return "—";
        long d = ChronoUnit.DAYS.between(dayMsk(pushedIso), LocalDate.now(MSK));
        return d <= ACTIVE_DAYS ? "🟢 активен" : d <= QUIET_DAYS ? "🟡 затишье" : "⚪ заморожен";
    }

    private static String generateStats(List<RepoRow> rows) {
        if (rows.isEmpty()) return "*Нет данных*";

        YearMonth now = YearMonth.now(MSK);
        List<YearMonth> months = new ArrayList<>();
        for (int i = MONTHS_SHOWN - 1; i >= 0; i--) {
            months.add(now.minusMonths(i));
        }
        int[] created = new int[MONTHS_SHOWN];
        int[] commits = new int[MONTHS_SHOWN];
        int[] active = new int[MONTHS_SHOWN];

        for (int i = 0; i < MONTHS_SHOWN; i++) {
            YearMonth m = months.get(i);
            for (RepoRow r : rows) {
                if (!r.created().isEmpty()
                        && YearMonth.from(Instant.parse(r.created()).atZone(MSK)).equals(m)) {
                    created[i]++;
                }
                if (r.stat() != null) {
                    int c = r.stat().months().getOrDefault(m.toString(), 0);
                    commits[i] += c;
                    if (c > 0) active[i]++;
                }
            }
        }

        int totalCommits = rows.stream().mapToInt(r -> r.stat() == null ? 0 : r.stat().commits()).sum();
        long active30 = rows.stream().filter(r -> activity(r.pushed()).startsWith("🟢")).count();
        int last12 = Arrays.stream(commits).sum();

        var sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "**Проектов:** %d · **Коммитов всего:** %d · **Активных за %d дн.:** %d · **В среднем:** %.0f коммитов в месяц\n\n",
                rows.size(), totalCommits, ACTIVE_DAYS, active30, last12 / (double) MONTHS_SHOWN));

        sb.append("#### 📅 По месяцам\n\n");
        sb.append("| Месяц | Новых проектов | Коммитов | Активных проектов |\n");
        sb.append("|-------|----------------|----------|-------------------|\n");
        for (int i = MONTHS_SHOWN - 1; i >= 0; i--) {
            sb.append("| ").append(months.get(i)).append(" | ").append(created[i]).append(" | ")
                    .append(commits[i]).append(" | ").append(active[i]).append(" |\n");
        }

        int maxCommits = Arrays.stream(commits).max().orElse(0);
        int yMax = Math.max(1, (int) Math.ceil(maxCommits * 1.1));
        StringJoiner xs = new StringJoiner(", ");
        StringJoiner ys = new StringJoiner(", ");
        for (int i = 0; i < MONTHS_SHOWN; i++) {
            xs.add("\"" + months.get(i) + "\"");
            ys.add(String.valueOf(commits[i]));
        }
        sb.append("\n```mermaid\nxychart-beta\n")
                .append("    title \"Коммиты по месяцам\"\n")
                .append("    x-axis [").append(xs).append("]\n")
                .append("    y-axis \"Коммитов\" 0 --> ").append(yMax).append("\n")
                .append("    bar [").append(ys).append("]\n```\n\n");

        sb.append("#### 🗂 Все проекты\n\n");
        sb.append("| Проект | Тема | Стек | Создан | Изменён | Коммитов | Статус | ⚡ |\n");
        sb.append("|--------|------|------|--------|---------|----------|--------|----|\n");

        List<RepoRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(RepoRow::pushed).reversed());
        for (RepoRow r : sorted) {
            String theme = r.category() == null ? "—" : cell(r.category());
            String stack = r.stat() == null || r.stat().langs().isEmpty() ? "—"
                    : cell(String.join(", ", r.stat().langs()));
            int c = r.stat() == null ? 0 : r.stat().commits();
            String speed = "—";
            if (r.facts() != null) {
                double o = Metrics.of(r.facts(), r.plan()).overall();
                speed = String.format(Locale.ROOT, "%.2fx %s", o, status(o));
            }
            sb.append("| [**").append(r.title()).append("**](").append(r.url()).append(")")
                    .append(" | ").append(theme)
                    .append(" | ").append(stack)
                    .append(" | ").append(dot(r.created()))
                    .append(" | ").append(dot(r.pushed()))
                    .append(" | ").append(c)
                    .append(" | ").append(activity(r.pushed()))
                    .append(" | ").append(speed)
                    .append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    record MetaInfo(String title, String description, String category) {}
    record Facts(int commits, String first, String last, long additions) {}
    record Plan(int total, int completed, String current, Integer plannedDays) {}
    record Analysis(JSONObject card, Plan plan) {}
    record Enriched(JSONObject card, Facts facts, Plan plan) {}
    record ProjectInfo(String title, String description, JSONObject card, String url, Facts facts, Plan plan) {}
    record Stat(int commits, String first, String last, List<String> langs, Map<String, Integer> months) {}
    record RepoRow(String title, String url, String category, String created, String pushed,
                   Stat stat, Facts facts, Plan plan) {}

    record Metrics(long days, double commitsPerDay, Double linesPerDay, double commitSpeed,
                   Double intensity, Integer planPercent, Double planSpeed, double overall) {
        static Metrics of(Facts f, Plan p) {
            long days = Math.max(1, ChronoUnit.DAYS.between(day(f.first()), day(f.last())) + 1);
            double cpd = f.commits() / (double) days;
            double commitSpeed = cpd / BASE_COMMITS;
            Double lpd = f.additions() >= 0 ? Double.valueOf(f.additions() / (double) days) : null;
            Double intensity = lpd == null ? null : Double.valueOf(lpd / BASE_LINES);

            Integer pct = null;
            Double planSpeed = null;
            if (p != null) {
                pct = (int) Math.round(100.0 * p.completed() / p.total());
                if (p.plannedDays() != null) {
                    planSpeed = (p.plannedDays() * (double) p.completed() / p.total()) / days;
                }
            }

            double sum = W_COMMIT * commitSpeed;
            double weight = W_COMMIT;
            if (intensity != null) { sum += W_INTENSITY * intensity; weight += W_INTENSITY; }
            if (planSpeed != null) { sum += W_PLAN * planSpeed; weight += W_PLAN; }

            return new Metrics(days, cpd, lpd, commitSpeed, intensity, pct, planSpeed, sum / weight);
        }
    }
}