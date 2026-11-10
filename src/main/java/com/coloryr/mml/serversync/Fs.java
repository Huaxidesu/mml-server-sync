package com.coloryr.mml.serversync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 清单路径、删除与 zip 解压的安全工具。 */
final class Fs {
    private Fs() {}

    static Path normalizeRelative(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        if (value.isEmpty()) {
            throw new IOException(Text.of(Msg.THE_MANIFEST_CONTAINS_AN_EMPTY_PATH));
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException(Text.of(Msg.MANIFEST_PATH_MUST_NOT_BE_ABSOLUTE) + raw);
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IOException(Text.of(Msg.MANIFEST_PATH_MUST_NOT_CONTAIN) + raw);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IOException(Text.of(Msg.MANIFEST_PATH_IS_EMPTY) + raw);
        }
        Path out = null;
        for (String part : parts) {
            out = out == null ? java.nio.file.Paths.get(part) : out.resolve(part);
        }
        return out;
    }

    static Path resolveSafe(Path root, String raw) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path rel = normalizeRelative(raw);
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException(Text.of(Msg.PATH_ESCAPES_THE_TARGET_DIRECTORY) + raw);
        }
        return target;
    }

    /**
     * 路径比较用的规范键：统一分隔符、折叠大小写、去掉 `./` 与空段。
     *
     * 只用于比较，不用于落盘：返回的是小写键，在区分大小写的文件系统上不能直接解析。
     * 绝对路径、盘符与含 `..` 的写法返回空串，由调用方按无效路径处理。
     *
     * # 返回值
     *
     * 规范键；路径无效或越界时返回空串
     */
    static String pathKey(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        if (value.isEmpty() || value.startsWith("/")
                || (value.length() >= 2 && value.charAt(1) == ':'
                && Character.isLetter(value.charAt(0)))) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return "";
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 删除目录，但跳过 `keep` 判定为真的文件；目录里还有被保护文件时保留该目录。
     *
     * - `dir`: 要清理的目录
     * - `root`: 游戏目录，用于算出相对路径交给 `keep`
     * - `keep`: 入参是相对 `root` 的路径键，返回 true 表示保留
     *
     * # 返回值
     *
     * 被保留的文件数
     */
    static int deleteDirectory(Path dir, Path root, Predicate<String> keep) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        Path base = root.toAbsolutePath().normalize();
        final int[] kept = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String key = pathKey(base.relativize(file.toAbsolutePath().normalize()).toString());
                if (!key.isEmpty() && keep.test(key)) {
                    kept[0]++;
                    return FileVisitResult.CONTINUE;
                }
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                try (Stream<Path> children = Files.list(directory)) {
                    if (children.findAny().isPresent()) {
                        // 还有被保护的文件：目录必须留下
                        return FileVisitResult.CONTINUE;
                    }
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        return kept[0];
    }

    /** zip 里没有任何文件或目录条目；用于「空包 + Over=true = 删除目录」的显式删除 */
    static boolean isEmptyZip(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            return !file.entries().hasMoreElements();
        }
    }

    static void extractZip(Path zip, Path target, boolean base) throws IOException {
        extractZip(zip, target, base, null);
    }

    /**
     * 解压 zip；`skip` 判定为真的条目不解压（仍做越界校验），用于让屏蔽规则盖住归档内容。
     *
     * - `skip`: 入参是压缩包内的条目名；为 null 表示全部解压
     */
    static void extractZip(Path zip, Path target, boolean base, Predicate<String> skip)
            throws IOException {
        if (!zip.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException(Text.of(Msg.ONLY_ZIP_ARCHIVES_ARE_SUPPORTED) + zip);
        }
        List<ZipEntry> entries = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement());
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                resolveSafe(target, name);
                if (base) {
                    checkBaseEntry(name);
                }
            }
            for (ZipEntry entry : entries) {
                Cancels.check();
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                Path out = resolveSafe(target, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (skip != null && skip.test(name)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream input = file.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(out, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void checkBaseEntry(String raw) throws IOException {
        String value = raw.replace('\\', '/').replaceAll("^/+", "");
        String[] parts = value.split("/", -1);
        String first = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        if (first.equals("game.json") || first.equals("icon.png")
                || first.equals("server.json") || first.equals("server.json.sha1")
                || first.equals("server.json.old") || first.equals("server_instance_ids.json")) {
            throw new IOException(Text.of(Msg.THE_BASE_PACK_MUST_NOT_OVERWRITE_LAUNCHER_METADATA) + raw);
        }
        if (first.equals(".minecraft") && parts.length > 1) {
            String second = parts[1].toLowerCase(Locale.ROOT);
            if (second.equals("server.json") || second.equals("server.json.sha1")
                    || second.equals("server.json.old")
                    || second.equals("server_instance_ids.json")) {
                throw new IOException(Text.of(Msg.THE_BASE_PACK_MUST_NOT_OVERWRITE_THE_SERVER_MANIFEST) + raw);
            }
        }
    }

    static boolean under(String dir, String ancestor) {
        return dir.startsWith(ancestor) && dir.length() > ancestor.length()
                && dir.charAt(ancestor.length()) == '/';
    }

    static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
