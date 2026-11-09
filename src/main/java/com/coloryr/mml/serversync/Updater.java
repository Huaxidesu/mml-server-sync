package com.coloryr.mml.serversync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自更新：从 index.json 取最新版本，把新 jar 下载到 jar 同目录的 update/ 下。
 *
 * 索引地址的优先级：命令行 / 系统属性 / 环境变量显式指定 > jar 同目录的
 * {@link #INDEX_FILE} 配置文件 > {@link #DEFAULT_INDEX}（本项目 GitHub Release）。
 * 整合包只要随包放一份配置文件，就能把自更新指到自己的发布仓库。
 *
 * 不覆盖正在运行的 jar —— 那个文件被 JVM 独占，进程活着就换不掉；新版本留在目录里，
 * 下次启动由 {@link Bootstrap} 用独立类加载器装上，见 {@link UpdateClassLoader}。
 *
 * 整个过程不影响同步：任何一步失败只记一行日志。
 */
final class Updater {
    /** 没有配置文件时回落到本项目 fork 的 GitHub Release 资产 */
    static final String DEFAULT_INDEX =
            "https://github.com/Huaxidesu/mml-server-sync/releases/latest/download/index.json";

    /** jar 同目录的索引配置文件：一行一个地址，# 开头为注释，取第一条有效行 */
    static final String INDEX_FILE = "mml-server-sync-index.txt";

    /**
     * 原始更新器 jar 路径的系统属性键。
     *
     * 入口（premain / main）在启动时用类加载源捕获并写入；从 update/ 包运行时
     * 类加载源指向 update/ 里的副本，而 JVM 独占锁着的、清单条目对应的
     * 是 -javaagent / -jar 指定的那份原始 jar，两者不能混用。
     */
    static final String SELF_JAR_KEY = "mml.serverSync.selfJar";

    /** 更新包目录名，放在 jar 同目录下 */
    private static final String UPDATE_DIR = "update";
    /** 更新包文件名前缀，中间是版本号 */
    private static final String FILE_PREFIX = "mml-server-sync-";
    /** 更新包文件名后缀 */
    private static final String FILE_SUFFIX = ".jar";
    /** 下载中的临时文件后缀 */
    private static final String PART_SUFFIX = ".part";
    /** 索引里给了 SHA256 时落下的副本，加载前用同一份值复核 */
    private static final String SHA256_SIDECAR = ".sha256";

    private Updater() {}

    /**
     * 后台检查一次更新
     *
     * 守护线程：命令行模式跑完同步后由引导方短暂 join，游戏模式则让它自己跑完。
     *
     * - `enabled`: 是否开启自更新
     * - `index`: 索引地址；空串用 {@link #DEFAULT_INDEX}
     *
     * # 返回值
     *
     * 后台线程；没有开启自更新时为 null
     */
    static Thread start(boolean enabled, String index) {
        if (!enabled) {
            return null;
        }
        Thread thread = new Thread(() -> {
            try {
                check(index);
            } catch (Throwable e) {
                Log.info(Text.of(Msg.UPDATE_CHECK_FAILED_IGNORING) + describe(e));
            }
        }, "mml-server-sync-update");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 拉索引并把新版本下载到 update 目录
     *
     * - `indexText`: 索引地址；空串用默认地址
     */
    static void check(String indexText) throws IOException {
        Path jar = jarPath();
        if (jar == null) {
            Log.info(Text.of(Msg.NOT_RUNNING_FROM_A_JAR_SKIPPING_SELF_UPDATE));
            return;
        }
        String index = indexText == null ? "" : indexText.trim();
        if (index.isEmpty()) {
            index = systemIndex();
        }
        if (index.isEmpty()) {
            index = configIndex();
        }
        if (index.isEmpty()) {
            index = DEFAULT_INDEX;
            Log.info(Text.of(Msg.UPDATE_INDEX_DEFAULT_USED, index));
        }
        byte[] body;
        try {
            body = fetch(index);
        } catch (IOException e) {
            Log.info(Text.of(Msg.UPDATE_INDEX_UNAVAILABLE_REASON, index, describe(e)));
            return;
        }
        if (looksLikeHtml(body)) {
            // 索引地址被指到网页（常见于域名首页）时说清楚，别只报一个 JSON 字符错
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_A_WEB_PAGE_SKIPPING, index));
            return;
        }
        Map<String, Object> root;
        try {
            root = Json.map(Json.parse(body));
        } catch (IOException e) {
            Log.info(Text.of(Msg.UPDATE_INDEX_UNAVAILABLE_REASON, index, describe(e)));
            return;
        }
        if (root == null) {
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_NOT_A_JSON_OBJECT_SKIPPING));
            return;
        }
        String version = Json.string(root, "version", "Version").trim();
        // 允许相对地址：index.json 与 jar 放同一个目录时，整包传到哪都能用
        String url = resolveUrl(index, Json.string(root, "url", "Url").trim());
        String sha1 = Json.string(root, "sha1", "Sha1").trim();
        String sha256 = Json.string(root, "sha256", "Sha256").trim();
        if (version.isEmpty() || url.isEmpty()) {
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_MISSING_VERSION_URL_SKIPPING));
            return;
        }
        if (compare(version, Main.VERSION) <= 0) {
            return;
        }
        Path dir = updateDir(jar);
        Path target = dir.resolve(FILE_PREFIX + safeVersion(version) + FILE_SUFFIX);
        if (Files.isRegularFile(target) && sameFile(target, sha1, sha256)) {
            return;
        }
        Log.info(Text.of(Msg.UPDATER_NEW_VERSION, version, Main.VERSION));
        Files.createDirectories(dir);
        download(url, target, sha1, sha256);
        writeSidecar(target, Verify.SHA1_SIDECAR, sha1);
        writeSidecar(target, SHA256_SIDECAR, sha256);
        prune(dir, target);
        Log.info(Text.of(Msg.UPDATER_DOWNLOADED, target));
    }

    /**
     * 读命令行之外的显式索引来源：JVM 属性优先，其次环境变量。
     *
     * javaagent 模式由 {@code Agent} 把它们翻成 {@code --update-index} 传下来；
     * 独立运行（{@code -jar}）没有那一步，所以这里再兜底一次，两种模式行为一致。
     *
     * # 返回值
     *
     * 显式指定的索引地址；都没有时是空串
     */
    private static String systemIndex() {
        String property = System.getProperty("mml.serverSync.updateIndex", "").trim();
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv("MML_SERVER_SYNC_INDEX");
        return env == null ? "" : env.trim();
    }

    /**
     * 读 jar 同目录的索引配置文件。
     *
     * 找不到、读不了、内容为空都返回空串，由调用方回落到 {@link #DEFAULT_INDEX}。
     * 只取第一条非空、非注释行，方便玩家直接手写或由整合包随包覆盖。
     *
     * # 返回值
     *
     * 配置文件里的索引地址；未配置时是空串
     */
    private static String configIndex() {
        for (Path dir : configDirs()) {
            Path file = dir.resolve(INDEX_FILE);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#")) {
                        continue;
                    }
                    Log.info(Text.of(Msg.UPDATE_INDEX_FROM_FILE, file, value));
                    return value;
                }
            } catch (IOException e) {
                Log.info(Text.of(Msg.UPDATE_INDEX_FILE_UNREADABLE, file, describe(e)));
            }
        }
        return "";
    }

    /**
     * 配置文件可能所在的目录，按优先级排列。
     *
     * 入口捕获的原始 jar 同目录最可靠；从 update/ 包运行时再补它的上级目录；
     * 最后兜底用工作目录，覆盖其它启动方式。
     *
     * # 返回值
     *
     * 待查目录列表
     */
    private static List<Path> configDirs() {
        List<Path> out = new ArrayList<>();
        try {
            Path original = originalJarPath();
            if (original != null) {
                Path parent = original.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    out.add(parent);
                }
            }
        } catch (RuntimeException ignored) {
            // 系统属性里的路径不合法时跳过这一项
        }
        Path running = jarPath();
        if (running != null) {
            Path parent = running.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                out.add(parent);
                if (UPDATE_DIR.equalsIgnoreCase(String.valueOf(parent.getFileName()))
                        && parent.getParent() != null) {
                    out.add(parent.getParent());
                }
            }
        }
        out.add(Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        return out;
    }

    /** 把原始 jar 路径记进系统属性，供同步计划跳过自身条目；须在入口最先调用 */
    static void captureSelfJar() {
        Path jar = jarPath();
        if (jar != null) {
            System.setProperty(SELF_JAR_KEY, jar.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 原始 jar（被 -javaagent / -jar 指定、JVM 锁住的那份）的路径。
     *
     * 顺序：入口捕获的系统属性 → 当前类加载源（不经 update/ 运行时就是原始 jar）→
     * 从 update/ 包运行时到它的上级目录找 `mml-server-sync*.jar`。最后这条兜底覆盖
     * 「旧版本入口还没有捕获逻辑」的过渡期：第一次自更新到本版本后，运行代码来自
     * update/，而锁住的原始 jar 是旧入口那份，属性尚未由它写入。
     *
     * # 返回值
     *
     * 原始 jar 路径；找不到时返回 null
     */
    static Path originalJarPath() {
        String property = System.getProperty(SELF_JAR_KEY, "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property);
        }
        Path running = jarPath();
        if (running == null) {
            return null;
        }
        Path parent = running.toAbsolutePath().normalize().getParent();
        if (parent == null || !UPDATE_DIR.equalsIgnoreCase(parent.getFileName().toString())) {
            return running;
        }
        Path originDir = parent.getParent();
        if (originDir == null) {
            return null;
        }
        Path preferred = originDir.resolve("mml-server-sync.jar");
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(originDir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // 找不到就当没有，跳过逻辑退化为不跳过
        }
        return null;
    }

    /**
     * 当前运行的 jar 路径
     *
     * # 返回值
     *
     * jar 文件路径；以 classes 目录运行时返回 null
     */
    static Path jarPath() {
        try {
            java.security.CodeSource source = Updater.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 更新目录，与当前 jar 同级
     *
     * - `jar`: 当前 jar
     *
     * # 返回值
     *
     * `<jar 所在目录>/update`
     */
    static Path updateDir(Path jar) {
        return jar.toAbsolutePath().normalize().getParent().resolve(UPDATE_DIR);
    }

    /**
     * 下载新 jar
     *
     * - `url`: 新 jar 地址
     * - `target`: 落盘位置，即 update/mml-server-sync-<版本>.jar
     * - `sha1`: SHA1；空串表示不校验
     * - `sha256`: SHA256；空串表示不校验
     */
    private static void download(String url, Path target, String sha1, String sha256)
            throws IOException {
        Path part = target.resolveSibling(target.getFileName().toString() + PART_SUFFIX);
        try {
            Files.deleteIfExists(part);
            URLConnection connection = open(url);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
            if (!sha1.isEmpty() && !Downloader.hash(part, "SHA-1").equalsIgnoreCase(sha1)) {
                throw new IOException(Text.of(Msg.NEW_JAR_SHA1_MISMATCH));
            }
            if (!sha256.isEmpty() && !Downloader.hash(part, "SHA-256").equalsIgnoreCase(sha256)) {
                throw new IOException(Text.of(Msg.NEW_JAR_SHA256_MISMATCH));
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** 已经下过并且对得上：索引给了哈希就比哈希，没有就交给结构校验 */
    private static boolean sameFile(Path target, String sha1, String sha256) {
        if (!sha1.isEmpty() || !sha256.isEmpty()) {
            return Downloader.matches(target, sha1, sha256);
        }
        return Verify.check(target) == null;
    }

    /** 把索引里的哈希落到 jar 旁边，下次启动加载前用同一份值复核 */
    private static void writeSidecar(Path jar, String suffix, String value) {
        Path file = jar.resolveSibling(jar.getFileName().toString() + suffix);
        try {
            if (value == null || value.trim().isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 写不下去只影响下次启动的哈希复核，不影响本次更新
        }
    }

    /**
     * 删掉更新目录里比新版本旧的包，只留一份
     *
     * - `dir`: update 目录
     * - `keep`: 刚下载好的那份
     */
    private static void prune(Path dir, Path keep) {
        String keepName = keep.getFileName().toString();
        String keepVersion = keepName.substring(FILE_PREFIX.length(),
                keepName.length() - FILE_SUFFIX.length());
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                if (file.equals(keep)) {
                    continue;
                }
                String name = file.getFileName().toString();
                String version = name.substring(FILE_PREFIX.length(),
                        name.length() - FILE_SUFFIX.length());
                if (compare(version, keepVersion) < 0) {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(file.resolveSibling(name + Verify.SHA1_SIDECAR));
                    Files.deleteIfExists(file.resolveSibling(name + SHA256_SIDECAR));
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响更新
        }
    }

    /**
     * 把索引里的下载地址补成绝对地址
     *
     * - `base`: 索引自身的地址
     * - `url`: 索引里写的地址；已是绝对地址时原样返回
     *
     * # 返回值
     *
     * 绝对地址；解析失败时返回原值
     */
    private static String resolveUrl(String base, String url) {
        if (url == null || url.isEmpty()
                || url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return url == null ? "" : url;
        }
        try {
            return new URL(new URL(base), url).toString();
        } catch (IOException e) {
            return url;
        }
    }

    /**
     * 拉取索引
     *
     * - `text`: HTTP(S) 或 file 地址
     *
     * # 返回值
     *
     * 响应体内容
     */
    private static byte[] fetch(String text) throws IOException {
        URLConnection connection = open(text);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static URLConnection open(String text) throws IOException {
        URL url;
        try {
            url = new URL(text);
        } catch (IOException e) {
            throw new IOException(Text.of(Msg.INVALID_URL) + text, e);
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MML-ServerSync/" + Main.VERSION);
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + text);
            }
        }
        return connection;
    }

    /** 版本号里只留做文件名的安全字符 */
    private static String safeVersion(String version) {
        String out = version.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.isEmpty() ? "unknown" : out;
    }

    /**
     * 版本比较，只比数字段，非数字部分忽略
     *
     * - `left`: 左侧版本，如 1.2.0
     * - `right`: 右侧版本
     *
     * # 返回值
     *
     * 左侧更新时为正数，相同时为 0
     */
    static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        String[] parts = (version == null ? "" : version.trim()).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            try {
                out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** 响应体开头是 '<'（跳过 BOM 与空白）时，基本可以确定是网页而不是 JSON */
    private static boolean looksLikeHtml(byte[] data) {
        int index = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            index = 3;
        }
        while (index < data.length && ((data[index] & 0xFF) == ' ' || (data[index] & 0xFF) == '\n'
                || (data[index] & 0xFF) == '\r' || (data[index] & 0xFF) == '\t')) {
            index++;
        }
        return index < data.length && data[index] == '<';
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
