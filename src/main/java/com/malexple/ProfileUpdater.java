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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileUpdater {
    private static final String USERNAME = "malexple";
    private static final String TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String GEMINI_KEY = System.getenv("GEMINI_API_KEY");
    private static final String GEMINI_MODEL = envOr("GEMINI_MODEL", "gemini-3.8-flash");
    private static final int MAX_GENERATIONS = 15;
    private static final int README_LIMIT = 12_000;
    private static final Path CACHE_PATH = Paths.get("ai-descriptions.json");
    private static final Yaml yaml = new Yaml();

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "<!--\\s*PROJECTS_TABLE_START:\\s*(.+?)\\s*-->(.*?)<!--\\s*PROJECTS_TABLE_END\\s*-->",
            Pattern.DOTALL
    );

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        System.out.println("🔍 Загружаю список репозиториев...");

        HttpRequest request = githubRequest("https://api.github.com/users/" + USERNAME
                + "/repos?per_page=100&sort=updated", "application/vnd.github.v3+json");

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Ошибка API: " + response.body());
            System.exit(1);
        }

        JSONObject cache = loadCache();
        String cacheBefore = cache.toString();
        int[] budget = {MAX_GENERATIONS};
        if (GEMINI_KEY == null || GEMINI_KEY.isBlank()) {
            System.out.println("ℹ GEMINI_API_KEY не задан — подробные описания не генерируются");
        }

        JSONArray repos = new JSONArray(response.body());
        Map<String, List<ProjectInfo>> categorized = new LinkedHashMap<>();
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
                String details = resolveDetails(client, repoName, meta, pushedAt, cache, budget);

                categorized.computeIfAbsent(meta.category(), k -> new ArrayList<>())
                        .add(new ProjectInfo(title, desc, details, url));
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

            String table = generateTable(projects);
            String replacement = "<!-- PROJECTS_TABLE_START: " + section + " -->\n"
                    + table
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

        Files.writeString(readmePath, updated.toString(), StandardCharsets.UTF_8);
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
                .header("Authorization", "token " + TOKEN)
                .header("Accept", accept)
                .header("User-Agent", "ProfileUpdater/1.0")
                .build();
    }

    private static MetaInfo fetchMetadata(HttpClient client, String repoName) {
        try {
            HttpRequest req = githubRequest("https://api.github.com/repos/" + USERNAME + "/" + repoName
                    + "/contents/metadataInfo.yaml", "application/vnd.github.v3+json");

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
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

    private static String fetchReadme(HttpClient client, String repoName) throws Exception {
        HttpRequest req = githubRequest("https://api.github.com/repos/" + USERNAME + "/" + repoName
                + "/readme", "application/vnd.github.raw+json");
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return null;
        }
        String body = resp.body();
        return body.length() > README_LIMIT ? body.substring(0, README_LIMIT) : body;
    }

    // ---------- Подробное описание: YAML > кэш > Gemini ----------

    private static String resolveDetails(HttpClient client, String repoName, MetaInfo meta,
                                         String pushedAt, JSONObject cache, int[] budget) {
        if (meta.details() != null && !meta.details().isEmpty()) {
            return meta.details();
        }

        JSONObject cached = cache.optJSONObject(repoName);
        String fallback = cached != null ? cached.optString("text", "") : "";

        if (GEMINI_KEY == null || GEMINI_KEY.isBlank()) {
            return fallback;
        }

        String stamp = pushedAt + "|" + GEMINI_MODEL;
        if (cached != null && stamp.equals(cached.optString("stamp"))) {
            return fallback;
        }
        if (budget[0] <= 0) {
            System.out.println("ℹ " + repoName + ": лимит генераций за запуск исчерпан");
            return fallback;
        }
        budget[0]--;

        try {
            String readme = fetchReadme(client, repoName);
            if (readme == null || readme.isBlank()) {
                return fallback;
            }
            String text = askGemini(client, repoName, meta, readme);
            if (text == null || text.isBlank()) {
                return fallback;
            }
            cache.put(repoName, new JSONObject().put("stamp", stamp).put("text", text));
            System.out.println("🤖 " + repoName + ": описание сгенерировано");
            return text;
        } catch (Exception e) {
            System.err.println("⚠ Gemini " + repoName + ": " + e.getMessage());
            return fallback;
        }
    }

    private static String askGemini(HttpClient client, String repoName, MetaInfo meta, String readme)
            throws Exception {
        String prompt = "Ты пишешь краткую аннотацию проекта для витрины GitHub-профиля.\n"
                + "Правила: русский язык, 2-3 предложения, не более 350 символов, "
                + "простой текст без markdown, без символа |, без эмодзи. "
                + "Опирайся только на факты из README, ничего не выдумывай. "
                + "Объясни, какую проблему решает проект и чем он полезен.\n\n"
                + "Проект: " + repoName + "\n"
                + "Краткое описание: " + (meta.description() == null ? "" : meta.description()) + "\n\n"
                + "README:\n" + readme;

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", new JSONObject().put("temperature", 0.3));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                        + GEMINI_MODEL + ":generateContent"))
                .header("x-goog-api-key", GEMINI_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            String b = resp.body();
            System.err.println("⚠ Gemini HTTP " + resp.statusCode() + ": "
                    + (b.length() > 200 ? b.substring(0, 200) : b));
            return null;
        }

        return new JSONObject(resp.body())
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim();
    }

    // ---------- Кэш ----------

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

    // ---------- Утилиты и таблица ----------

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

    record MetaInfo(String title, String description, String category, String details) {}
    record ProjectInfo(String title, String description, String details, String url) {}
}