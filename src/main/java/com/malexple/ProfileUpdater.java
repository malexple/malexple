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

        // 1. Получаем все публичные репозитории
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/users/" + USERNAME
                        + "/repos?per_page=100&sort=updated"))
                .header("Authorization", "token " + TOKEN)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ProfileUpdater/1.0")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Ошибка API: " + response.body());
            System.exit(1);
        }

        JSONArray repos = new JSONArray(response.body());
        Map<String, List<ProjectInfo>> categorized = new LinkedHashMap<>();
        List<String> processed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 2. Для каждого репозитория читаем metadataInfo.yaml
        for (int i = 0; i < repos.length(); i++) {
            JSONObject repo = repos.getJSONObject(i);
            String repoName = repo.getString("name");

            // Пропускаем профиль, форки, приватные
            if (repoName.equals(USERNAME) || repo.optBoolean("fork") || repo.optBoolean("private")) {
                continue;
            }

            String url = repo.getString("html_url");

            var meta = fetchMetadata(client, repoName);

            if (meta != null && meta.category() != null && !meta.category().isEmpty()) {
                String title = meta.title() != null && !meta.title().isEmpty() ? meta.title() : repoName;
                String desc = meta.description() != null ? meta.description() : "";

                categorized.computeIfAbsent(meta.category(), k -> new ArrayList<>())
                        .add(new ProjectInfo(title, desc, url));
                processed.add(repoName);
                System.out.println("✓ " + repoName + " → " + meta.category());
            } else {
                skipped.add(repoName);
            }
        }

        System.out.println("\n📊 Обработано: " + processed.size() + " проектов");
        System.out.println("📊 Пропущено (нет метаданных): " + skipped.size() + " проектов");
        System.out.println("📊 Категорий: " + categorized.size());

        // 3. Обновляем README профиля
        Path readmePath = Paths.get("README.md");
        if (!Files.exists(readmePath)) {
            System.err.println("README.md не найден!");
            System.exit(1);
        }

        String readme = Files.readString(readmePath);
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

        // Категории из metadataInfo.yaml, для которых в README нет маркера
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

        // 4. Выводим список проектов без метаданных
        if (!skipped.isEmpty()) {
            System.out.println("\n⚠️  Проекты без metadataInfo.yaml:");
            skipped.forEach(s -> System.out.println("   - " + s));
        }
    }

    // Читаем metadataInfo.yaml из репозитория
    private static MetaInfo fetchMetadata(HttpClient client, String repoName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + USERNAME + "/" + repoName
                            + "/contents/metadataInfo.yaml"))
                    .header("Authorization", "token " + TOKEN)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "ProfileUpdater/1.0")
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null; // файла нет — пропускаем
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

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    // Генерируем таблицу для README
    private static String generateTable(List<ProjectInfo> projects) {
        if (projects.isEmpty()) return "*Нет проектов в этой категории*";

        var sb = new StringBuilder();
        sb.append("| Проект | Описание |\n");
        sb.append("|--------|----------|\n");

        for (var p : projects) {
            String desc = p.description().replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").trim();
            sb.append("| [**").append(p.title()).append("**](").append(p.url()).append(")");
            sb.append(" | ").append(desc.isEmpty() ? "—" : desc);
            sb.append(" |\n");
        }
        return sb.toString();
    }

    record MetaInfo(String title, String description, String category) {}
    record ProjectInfo(String title, String description, String url) {}
}