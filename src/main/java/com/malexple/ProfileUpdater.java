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
import java.time.ZoneOffset;
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
    private static final String PROMPT_VERSION = "v3";
    private static final int MAX_REFRESHES = 15;
    private static final int README_LIMIT = 12_000;
    private static final int ROADMAP_LIMIT = 6_000;
    private static final String GH_JSON = "application/vnd.github.v3+json";
    private static final Path CACHE_PATH = Paths.get("ai-descriptions.json");

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
    private static final Pattern VELOCITY_PATTERN = Pattern.compile(
            "<!--\\s*VELOCITY_TABLE_START\\s*-->(.*?)<!--\\s*VELOCITY_TABLE_END\\s*-->",
            Pattern.DOTALL
    );
    private static final Pattern LAST_PAGE = Pattern.compile("page=(\\d+)>;\\s*rel=\"last\"");

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
            System.out.println("ℹ GEMINI_API_KEY не задан — описания и скорость не обновляются");
        }

        JSONArray repos = new JSONArray(response.body());
        Map<String, List<ProjectInfo>> categorized = new LinkedHashMap<>();
        List<ProjectInfo> all = new ArrayList<>();
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

            var meta = fetchMetadata(client, repoName);

            if (meta != null && meta.category() != null && !meta.category().isEmpty()) {
                String title = meta.title() != null && !meta.title().isEmpty() ? meta.title() : repoName;
                String desc = meta.description() != null ? meta.description() : "";
                Enriched e = resolve(client, repoName, meta, pushedAt, cache, budget);

                var info = new ProjectInfo(title, desc, e.details(), url, e.facts(), e.plan());
                categorized.computeIfAbsent(meta.category(), k -> new ArrayList<>()).add(info);
                all.add(info);
                processed.add(repoName);
                System.out.println("✓ " + repoName + " → " + meta.category());
            } else {
                skipped.add(repoName);
            }
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

            String replacement = "<!-- PROJECTS_TABLE_START: " + section + " -->\n"
                    + generateTable(projects)
                    + "\n<!-- PROJECTS_TABLE_END -->";

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
        Matcher vm = VELOCITY_PATTERN.matcher(result);
        if (vm.find()) {
            String block = "<!-- VELOCITY_TABLE_START -->\n" + generateVelocityTable(all)
                    + "\n<!-- VELOCITY_TABLE_END -->";
            result = result.substring(0, vm.start()) + block + result.substring(vm.end());
        } else {
            System.out.println("ℹ В README нет маркеров VELOCITY_TABLE_START / VELOCITY_TABLE_END");
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
            return new MetaInfo(str(map, "title"), str(map, "description"),
                    str(map, "category"), str(map, "details"));
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

    // Факты из GitHub API: число коммитов, даты первого/последнего, добавленные строки
    private static Facts fetchFacts(HttpClient client, String repo) throws Exception {
        String base = "https://api.github.com/repos/" + USERNAME + "/" + repo;
        HttpResponse<String> r1 = client.send(githubRequest(base + "/commits?per_page=1", GH_JSON),
                HttpResponse.BodyHandlers.ofString());
        if (r1.statusCode() != 200) {
            return null;
        }
        JSONArray head = new JSONArray(r1.body());
        if (head.isEmpty()) {
            return null;
        }

        String last = commitDate(head.getJSONObject(0));
        String first = last;
        int commits = 1;

        Optional<String> link = r1.headers().firstValue("Link");
        if (link.isPresent()) {
            Matcher m = LAST_PAGE.matcher(link.get());
            if (m.find()) {
                commits = Integer.parseInt(m.group(1));
                HttpResponse<String> r2 = client.send(
                        githubRequest(base + "/commits?per_page=1&page=" + commits, GH_JSON),
                        HttpResponse.BodyHandlers.ofString());
                if (r2.statusCode() == 200) {
                    JSONArray oldest = new JSONArray(r2.body());
                    if (!oldest.isEmpty()) {
                        first = commitDate(oldest.getJSONObject(0));
                    }
                }
            }
        }
        return new Facts(commits, first, last, fetchAdditions(client, base));
    }

    private static String commitDate(JSONObject commit) {
        return commit.getJSONObject("commit").getJSONObject("author").getString("date");
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

    // ---------- Анализ: кэш > Gemini ----------

    private static Enriched resolve(HttpClient client, String repo, MetaInfo meta, String pushedAt,
                                    JSONObject cache, int[] budget) {
        JSONObject cached = cache.optJSONObject(repo);
        Enriched old = cached == null ? new Enriched("", null, null) : fromCache(cached);
        String manual = meta.details() != null && !meta.details().isEmpty() ? meta.details() : null;

        if (GEMINI_KEY == null || GEMINI_KEY.isBlank()) {
            return withManual(old, manual);
        }

        String stamp = PROMPT_VERSION + "|" + GEMINI_MODEL + "|" + pushedAt;
        if (cached != null && stamp.equals(cached.optString("stamp"))) {
            return withManual(old, manual);
        }
        if (cached != null) {
            if (budget[0] <= 0) {
                System.out.println("ℹ " + repo + ": лимит обновлений за запуск исчерпан");
                return withManual(old, manual);
            }
            budget[0]--;
        }

        try {
            Facts facts = fetchFacts(client, repo);
            String readme = fetchRaw(client, "https://api.github.com/repos/" + USERNAME + "/" + repo
                    + "/readme", README_LIMIT);
            if (facts == null || readme == null || readme.isBlank()) {
                return withManual(old, manual);
            }
            String roadmap = fetchRaw(client, "https://api.github.com/repos/" + USERNAME + "/" + repo
                    + "/contents/ROADMAP.md", ROADMAP_LIMIT);

            Analysis a = askGemini(client, repo, meta, readme, roadmap);
            if (a == null) {
                return withManual(old, manual);
            }
            Enriched fresh = new Enriched(a.details(), facts, a.plan());
            cache.put(repo, toCache(stamp, fresh));
            System.out.println("🤖 " + repo + ": проанализирован");
            return withManual(fresh, manual);
        } catch (Exception e) {
            System.err.println("⚠ Анализ " + repo + ": " + e.getMessage());
            return withManual(old, manual);
        }
    }

    private static Enriched withManual(Enriched e, String manual) {
        return manual == null ? e : new Enriched(manual, e.facts(), e.plan());
    }

    private static Analysis askGemini(HttpClient client, String repoName, MetaInfo meta,
                                      String readme, String roadmap) throws Exception {
        String prompt = "Проанализируй проект для витрины GitHub-профиля и верни ТОЛЬКО JSON такого вида:\n"
                + "{\"details\": string, \"plan\": null | {\"total_stages\": int, \"completed_stages\": int, "
                + "\"current_stage\": string, \"planned_duration_days\": int | null}}\n\n"
                + "details: русский язык, 2 предложения, не более 250 символов, простой текст без markdown, "
                + "без символа |, без эмодзи. Не повторяй краткое описание: добавь, как проект устроен, "
                + "на чём построен и кому полезен.\n"
                + "plan: заполняй только если в README или ROADMAP есть явный список этапов, фаз или чекбоксов. "
                + "completed_stages — этапы, отмеченные выполненными. current_stage — короткое название "
                + "текущего этапа. planned_duration_days — только если срок указан явно (недели переведи в дни), "
                + "иначе null. Если плана нет — plan: null.\n"
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
        String details = j.optString("details", "").trim();

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
        return new Analysis(details, plan);
    }

    // ---------- Кэш ----------

    private static JSONObject toCache(String stamp, Enriched e) {
        JSONObject o = new JSONObject().put("stamp", stamp).put("details", e.details());
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
        return new Enriched(c.optString("details", ""), f, p);
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

    // ---------- Утилиты и таблицы ----------

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

    private static String generateTable(List<ProjectInfo> projects) {
        if (projects.isEmpty()) return "*Нет проектов в этой категории*";

        var sb = new StringBuilder();
        sb.append("| Проект | Описание |\n");
        sb.append("|--------|----------|\n");

        for (var p : projects) {
            String desc = cell(p.description());
            String details = cell(p.details());
            sb.append("| [**").append(p.title()).append("**](").append(p.url()).append(")");
            sb.append(" | ").append(desc.isEmpty() ? "—" : desc);
            if (!details.isEmpty()) {
                sb.append("<br><sub>").append(details).append("</sub>");
            }
            sb.append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String generateVelocityTable(List<ProjectInfo> projects) {
        record Row(ProjectInfo p, Metrics m) {}
        List<Row> rows = new ArrayList<>();
        for (var p : projects) {
            if (p.facts() != null) {
                rows.add(new Row(p, Metrics.of(p.facts(), p.plan())));
            }
        }
        if (rows.isEmpty()) return "*Нет данных*";
        rows.sort(Comparator.comparingDouble((Row r) -> r.m().overall()).reversed());

        var sb = new StringBuilder();
        sb.append("| Проект | Период | 🔄 Коммитов/день | 📅 План | 💻 Строк/день | ⚡ Скорость |\n");
        sb.append("|--------|--------|------------------|---------|---------------|-------------|\n");

        for (var r : rows) {
            Metrics m = r.m();
            Plan pl = r.p().plan();

            String plan = "—";
            if (pl != null) {
                plan = pl.completed() + "/" + pl.total() + " (" + m.planPercent() + "%)"
                        + (m.planSpeed() != null ? " " + status(m.planSpeed()) : "");
                if (!pl.current().isEmpty()) {
                    plan += "<br><sub>" + cell(pl.current()) + "</sub>";
                }
            }
            String lines = m.linesPerDay() == null ? "—"
                    : String.format(Locale.ROOT, "%.0f %s", m.linesPerDay(), status(m.intensity()));

            sb.append("| [**").append(r.p().title()).append("**](").append(r.p().url()).append(")");
            sb.append(" | ").append(m.days()).append(" дн.");
            sb.append(" | ").append(String.format(Locale.ROOT, "%.1f %s", m.commitsPerDay(), status(m.commitSpeed())));
            sb.append(" | ").append(plan);
            sb.append(" | ").append(lines);
            sb.append(" | ").append(String.format(Locale.ROOT, "**%.2fx** %s", m.overall(), label(m.overall())));
            sb.append(" |\n");
        }
        return sb.toString().stripTrailing();
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

    record MetaInfo(String title, String description, String category, String details) {}
    record Facts(int commits, String first, String last, long additions) {}
    record Plan(int total, int completed, String current, Integer plannedDays) {}
    record Analysis(String details, Plan plan) {}
    record Enriched(String details, Facts facts, Plan plan) {}
    record ProjectInfo(String title, String description, String details, String url, Facts facts, Plan plan) {}

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