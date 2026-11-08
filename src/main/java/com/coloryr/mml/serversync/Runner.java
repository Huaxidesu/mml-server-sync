package com.coloryr.mml.serversync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.coloryr.mml.serversync.host.Progress;

/**
 * 一次命令行同步的完整流程。
 *
 * 自更新会把 update 目录里的新 jar 用独立类加载器装进来，再反射调用 {@link #run}：
 * 这个类的全名与方法签名要跨版本保持不变，每个版本的 jar 里都带一份。
 * 它只做同步、不查更新、也绝不调用 System.exit；进度窗口在宿主没给的时候自己建，
 * 否则从更新包运行时界面会一直停在基包那一版的布局。
 */
public final class Runner {
    private Runner() {}

    /**
     * 按命令行参数跑一次同步
     *
     * - `args`: 命令行参数
     * - `progress`: 进度窗口；没有窗口时传 null 或 {@link Progress#NONE}
     *
     * # 返回值
     *
     * 进程退出码：0 成功或跳过，1 同步失败，2 参数错误
     */
    public static int run(String[] args, Progress progress) {
        Progress ui = progress == null ? Progress.NONE : progress;
        // 每次运行从干净状态开始；上一轮的取消要留到后台线程全部停下，所以这里清
        Cancels.clear();
        try {
            Options options = parse(args);
            if (options.help) {
                printHelp();
                return 0;
            }
            if (options.version) {
                System.out.println("mml-server-sync " + Main.VERSION);
                return 0;
            }
            ui = adopt(progress, options);
            if (options.manifest == null || options.manifest.trim().isEmpty()) {
                throw new UsageException(Text.of(Msg.MISSING_MANIFEST_URL_OR_FILE));
            }
            if (options.gameDir == null) {
                throw new UsageException(Text.of(Msg.MISSING_GAME_DIR_GAME_DIRECTORY));
            }
            options.gameDir = options.gameDir.toAbsolutePath().normalize();
            options.baseDir = options.baseDir == null
                    ? Main.defaultBaseDir(options.gameDir)
                    : options.baseDir.toAbsolutePath().normalize();
            options.stateDir = options.stateDir == null
                    ? options.gameDir.resolve(".mml-server-sync")
                    : options.stateDir.toAbsolutePath().normalize();
            Files.createDirectories(options.gameDir);
            Files.createDirectories(options.baseDir);
            Log.open(options.logFile == null
                    ? options.stateDir.resolve(Log.FILE_NAME)
                    : options.logFile.toAbsolutePath().normalize());
            Log.info("mml-server-sync " + Main.VERSION);
            // 真机上靠这行校准缩放：屏幕、DPI、AWT 变换与最终倍数都会写进日志
            UiScale.log();
            Log.info(Text.of(Msg.STARTING_SYNC_GAME_DIRECTORY) + options.gameDir);
            try {
                // 失败后可以选择重试，所以整段同步放在循环里；窗口与日志在整个循环里共用
                for (;;) {
                    try {
                        String summary = new Sync(options, ui).run();
                        if (!options.quiet) {
                            Log.info(summary);
                        }
                        ui.finish(true, summary);
                        break;
                    } catch (CancelledException e) {
                        // 用户选了「跳过更新」：退出码 0，游戏照常启动，下次启动补齐
                        ui.finish(false, e.getMessage());
                        Log.info(e.getMessage());
                        return 0;
                    } catch (SyncAbortException e) {
                        // 用户选了「结束游戏」：不按同步失败处理，直接以退出码 1 收尾
                        ui.finish(false, e.getMessage());
                        Log.info(e.getMessage());
                        return 1;
                    } catch (Exception e) {
                        Log.error(Text.of(Msg.SYNC_FAILED_2) + e.getMessage());
                        FailurePrompt.Choice choice = askAfterFailure(options, ui, e);
                        if (choice == null) {
                            // 弹不出来（--no-gui、GUI 不可用）且带 --lenient：
                            // 回到老行为，静默跳过本次同步，别把异常抛给 premain
                            ui.finish(false, Text.of(Msg.SYNC_INTERRUPTED_SYNC_SKIPPED));
                            Log.info(Text.of(Msg.SYNC_INTERRUPTED_SYNC_SKIPPED));
                            return 0;
                        }
                        if (choice == FailurePrompt.Choice.RETRY) {
                            // 上一次的取消标记不能带进新一轮；已下载的文件会在校验时跳过
                            Cancels.clear();
                            ui.stage(Text.of(Msg.RETRYING_SYNC));
                            continue;
                        }
                        ui.finish(false, e.getMessage());
                        if (choice == FailurePrompt.Choice.SKIP) {
                            Log.info(Text.of(Msg.UPDATE_FAILED_SKIPPED_BY_PLAYER));
                            return 0;
                        }
                        Log.info(Text.of(Msg.UPDATE_FAILED_CLOSED_BY_PLAYER));
                        // 3 = 玩家主动关闭，Agent 会按这个码给出「已关闭」而不是「更新失败」
                        return 3;
                    }
                }
            } finally {
                // 只解除暂停：取消状态留着，让可能在收尾的下载线程看到并停下
                Cancels.resume();
                Log.close();
            }
            return 0;
        } catch (UsageException e) {
            System.err.println(Log.PREFIX + Text.of(Msg.INVALID_ARGUMENT) + e.getMessage());
            System.err.println(Log.PREFIX + Text.of(Msg.USE_HELP_FOR_USAGE));
            return 2;
        } catch (Exception e) {
            System.err.println(Log.PREFIX + Text.of(Msg.SYNC_FAILED_2) + e.getMessage());
            return 1;
        }
    }

    /**
     * 同步失败后问玩家怎么办
     *
     * 只有「图形界面可用 + 显式开启 + IO 失败」才弹窗：网络断了、服务器繁忙、下载中断
     * 都属于 IO 失败。非 IO 失败一律丢回上层按失败收尾；弹不出窗时带 --lenient 就
     * 返回 {@code null} 表示静默跳过，否则同样丢回上层。
     *
     * - `options`: 本次选项
     * - `ui`: 进度窗口，弹窗拿它当父窗口
     * - `cause`: 失败原因
     *
     * # 返回值
     *
     * 玩家的选择，见 {@link FailurePrompt.Choice}；{@code null} 表示自动跳过本次更新
     */
    private static FailurePrompt.Choice askAfterFailure(Options options, Progress ui, Exception cause)
            throws Exception {
        if (!(cause instanceof java.io.IOException)) {
            throw cause;
        }
        boolean canPrompt = options.gui && options.askOnFailure
                && !java.awt.GraphicsEnvironment.isHeadless() && !ProgressWindow.guiUnavailable();
        if (!canPrompt) {
            if (options.lenient) {
                return null;
            }
            throw cause;
        }
        java.awt.Component parent = ui instanceof ProgressWindow
                ? ((ProgressWindow) ui).frame() : null;
        return FailurePrompt.ask(parent, cause.getMessage());
    }

    /**
     * 取本次运行真正使用的进度窗口：宿主传来的不是本包这一份时，换成自己建的。
     *
     * 从 update/ 包运行时窗口由宿主（基包）创建，而基包不随更新包变化，界面布局会
     * 一直停在基包那一版；同步逻辑在更新包里，界面也必须由最新的代码画。
     *
     * - `given`: 宿主传来的窗口，可能是空实现
     * - `options`: 本次运行的选项，用来判断要不要窗口
     *
     * # 返回值
     *
     * 实际使用的窗口；不需要窗口时原样返回 `given`
     */
    private static Progress adopt(Progress given, Options options) {
        Progress current = given == null ? Progress.NONE : given;
        if (current instanceof ProgressWindow) {
            return current;
        }
        if (!options.gui || java.awt.GraphicsEnvironment.isHeadless()) {
            return current;
        }
        Progress own = ProgressWindow.open(true, lingerMillis());
        if (own == Progress.NONE) {
            return current;
        }
        if (current != Progress.NONE) {
            // 宿主那一版的窗口先收掉，别和新窗口叠在一起
            try {
                current.finish(true, "");
            } catch (Throwable ignored) {
                // 关不掉不影响新窗口
            }
        }
        return own;
    }

    /** 窗口结束后停留多久：宿主经系统属性传过来，没传时按游戏模式处理（不停留） */
    private static int lingerMillis() {
        String value = System.getProperty(Bootstrap.LINGER_KEY, "").trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Options parse(String[] args) throws UsageException {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.help = true;
            } else if ("--version".equals(arg)) {
                options.version = true;
            } else if ("--force".equals(arg)) {
                options.force = true;
            } else if ("--dry-run".equals(arg)) {
                options.dryRun = true;
            } else if ("--no-delete".equals(arg)) {
                options.noDelete = true;
            } else if ("--quiet".equals(arg)) {
                options.quiet = true;
            } else if ("--lenient".equals(arg)) {
                options.lenient = true;
            } else if ("--ask-on-failure".equals(arg)) {
                options.askOnFailure = true;
            } else if (arg.startsWith("--ui-scale=")) {
                String raw = arg.substring("--ui-scale=".length());
                Double value = UiScale.parse(raw);
                if (value == null) {
                    throw new UsageException(Text.of(Msg.UI_SCALE_NEEDS_A_NUMBER, raw));
                }
                UiScale.override(value);
            } else if ("--no-gui".equals(arg)) {
                options.gui = false;
            } else if ("--gui".equals(arg)) {
                options.gui = true;
            } else if ("--no-ask".equals(arg)) {
                options.ask = false;
            } else if ("--no-update".equals(arg)) {
                options.update = false;
            } else if ("--update".equals(arg)) {
                options.update = true;
            } else if (arg.startsWith("--update-index=")) {
                options.updateIndex = arg.substring("--update-index=".length());
            } else if (arg.startsWith("--skip=")) {
                addSkips(options.skip, arg.substring("--skip=".length()));
            } else if (arg.startsWith("--skip-file=")) {
                options.skipFile = Paths.get(arg.substring("--skip-file=".length()));
            } else if (arg.startsWith("--manifest=")) {
                options.manifest = arg.substring("--manifest=".length());
            } else if (arg.startsWith("--game-dir=")) {
                options.gameDir = Paths.get(arg.substring("--game-dir=".length()));
            } else if (arg.startsWith("--base-dir=")) {
                options.baseDir = Paths.get(arg.substring("--base-dir=".length()));
            } else if (arg.startsWith("--state-dir=")) {
                options.stateDir = Paths.get(arg.substring("--state-dir=".length()));
            } else if (arg.startsWith("--curseforge-api-key=")) {
                options.apiKey = arg.substring("--curseforge-api-key=".length());
            } else if (arg.startsWith("--log=")) {
                options.logFile = Paths.get(arg.substring("--log=".length()));
            } else if (arg.startsWith("--threads=")) {
                options.threads = threadsOf(arg.substring("--threads=".length()));
            } else if ("--manifest".equals(arg) || "-m".equals(arg)) {
                options.manifest = value(args, ++i, arg);
            } else if ("--game-dir".equals(arg) || "-g".equals(arg)) {
                options.gameDir = Paths.get(value(args, ++i, arg));
            } else if ("--base-dir".equals(arg)) {
                options.baseDir = Paths.get(value(args, ++i, arg));
            } else if ("--state-dir".equals(arg)) {
                options.stateDir = Paths.get(value(args, ++i, arg));
            } else if ("--curseforge-api-key".equals(arg)) {
                options.apiKey = value(args, ++i, arg);
            } else if ("--log".equals(arg)) {
                options.logFile = Paths.get(value(args, ++i, arg));
            } else if ("--threads".equals(arg)) {
                options.threads = threadsOf(value(args, ++i, arg));
            } else if ("--update-index".equals(arg)) {
                options.updateIndex = value(args, ++i, arg);
            } else if ("--skip".equals(arg)) {
                addSkips(options.skip, value(args, ++i, arg));
            } else if ("--skip-file".equals(arg)) {
                options.skipFile = Paths.get(value(args, ++i, arg));
            } else if (!arg.startsWith("-")) {
                throw new UsageException(Text.of(Msg.UNKNOWN_ARGUMENT) + arg);
            } else {
                throw new UsageException(Text.of(Msg.UNKNOWN_ARGUMENT) + arg);
            }
        }
        if (options.apiKey.isEmpty()) {
            String env = System.getenv("CURSEFORGE_API_KEY");
            if (env != null) {
                options.apiKey = env;
            }
        }
        readSkipFile(options);
        return options;
    }

    /** 解析逗号分隔的 `--skip` 值，路径统一成 `/` 分隔 */
    private static void addSkips(List<String> target, String value) {
        for (String part : (value == null ? "" : value).split(",")) {
            String path = normalizePath(part);
            if (!path.isEmpty()) {
                target.add(path);
            }
        }
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    /**
     * 读 `--skip-file`：UTF-8，一行一个路径，空行与 `#` 开头的行忽略
     *
     * - `options`: 已经把 skipFile 填好的选项
     */
    private static void readSkipFile(Options options) throws UsageException {
        if (options.skipFile == null) {
            return;
        }
        Path file = options.skipFile.toAbsolutePath().normalize();
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String path = normalizePath(line);
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                options.skip.add(path);
            }
        } catch (java.io.IOException e) {
            throw new UsageException(Text.of(Msg.FAILED_TO_READ_SKIP_FILE) + e.getMessage());
        }
    }

    /** 解析线程数，限制在 1-64 */
    private static int threadsOf(String value) throws UsageException {
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            throw new UsageException(Text.of(Msg.THREADS_NEEDS_AN_INTEGER) + value);
        }
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException(option + Text.of(Msg.IS_MISSING_A_VALUE));
        }
        return args[index];
    }

    private static void printHelp() {
        System.out.println("mml-server-sync " + Main.VERSION);
        System.out.println(Text.of(Msg.STANDALONE_SERVER_INSTANCE_UPDATER_FOR_PRE_LAUNCH_UPDATES_FROM_NON_MML_LAUNCHERS));
        System.out.println();
        System.out.println(Text.of(Msg.JAVA_AGENT_RECOMMENDED));
        System.out.println(Text.of(Msg.JAVAAGENT_MML_SERVER_SYNC_JAR_URL_OF_SERVER_JSON));
        System.out.println();
        System.out.println(Text.of(Msg.COMMAND_LINE));
        System.out.println(Text.of(Msg.JAVA_JAR_MML_SERVER_SYNC_JAR_MANIFEST_URL_FILE_GAME_DIR_DIR_OPTIONS));
        System.out.println();
        System.out.println(Text.of(Msg.OPTIONS));
        System.out.println(Text.of(Msg.MANIFEST_M_URL_OR_LOCAL_PATH_OF_THE_MML_SERVER_JSON));
        System.out.println(Text.of(Msg.GAME_DIR_G_GAME_DIRECTORY_E_G_MINECRAFT_MODS_AND_CONFIGS_GO_HERE));
        System.out.println(Text.of(Msg.BASE_DIR_ROOT_FOR_THE_MML_BASE_PACK_PARENT_OF_MINECRAFT_OTHERWISE_GAME_DIR));
        System.out.println(Text.of(Msg.STATE_DIR_MANIFEST_STATE_DIRECTORY_DEFAULT_GAME_DIR_MML_SERVER_SYNC));
        System.out.println(Text.of(Msg.FORCE_SYNC_EVEN_WHEN_THE_PACK_VERSION_IS_UNCHANGED));
        System.out.println(Text.of(Msg.DRY_RUN_PRINT_THE_PLAN_ONLY_NO_DOWNLOAD_DELETE_EXTRACT));
        System.out.println(Text.of(Msg.NO_DELETE_DO_NOT_DELETE_OLD_FILES_OR_CONFIG_DIRECTORIES));
        System.out.println(Text.of(Msg.CURSEFORGE_API_KEY_CURSEFORGE_API_KEY_DEFAULTS_TO_CURSEFORGE_API_KEY));
        System.out.println(Text.of(Msg.LOG_SYNC_LOG_FILE_DEFAULT_STATE_DIR_MML_SERVER_SYNC_LOG));
        System.out.println(Text.of(Msg.THREADS_CONCURRENT_DOWNLOADS_1_64_DEFAULT_16));
        System.out.println(Text.of(Msg.SKIP_OPTIONAL_MODS_NOT_TO_UPDATE_MANIFEST_RELATIVE_PATHS_REPEATABLE_OR_COMMA_SEPARATED));
        System.out.println(Text.of(Msg.SKIP_FILE_READ_SKIP_PATHS_FROM_A_UTF_8_FILE_ONE_PER_LINE));
        System.out.println(Text.of(Msg.NO_GUI_DO_NOT_SHOW_THE_PROGRESS_WINDOW));
        System.out.println(Text.of(Msg.ASK_ON_FAILURE_PROMPT_FOR_A_CHOICE_WHEN_THE_UPDATE_FAILS));
        System.out.println(Text.of(Msg.NO_ASK_DO_NOT_ASK_ABOUT_OPTIONAL_MODS_UPDATE_ALL));
        System.out.println(Text.of(Msg.NO_UPDATE_DO_NOT_CHECK_FOR_SELF_UPDATES));
        System.out.println(Text.of(Msg.UPDATE_INDEX_SELF_UPDATE_INDEX_URL_DEFAULT));
        System.out.println(Text.of(Msg.QUIET_LESS_OUTPUT));
        System.out.println(Text.of(Msg.VERSION_SHOW_VERSION));
        System.out.println(Text.of(Msg.HELP_H_SHOW_HELP));
        System.out.println();
        System.out.println(Text.of(Msg.EXAMPLES));
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft");
        System.out.println("  java -jar mml-server-sync.jar -m ./server.json -g ./instance/.minecraft --base-dir ./instance");
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft --skip=mods/b.jar --skip=mods/c.jar");
        System.out.println();
        System.out.println(Text.of(Msg.NOTES));
        System.out.println(Text.of(Msg.SKIPPED_WHEN_PACKVERSION_IS_UNCHANGED_FORCE_OVERRIDES));
        System.out.println(Text.of(Msg.ONLY_FILES_ARCHIVES_BASE_OF_THE_MML_SERVER_JSON_ARE_READ));
        System.out.println(Text.of(Msg.CURSEFORGE_ENTRIES_WITHOUT_A_URL_FALL_BACK_TO_THE_CDN_MODRINTH_USES_ITS_PUBLIC_API));
        System.out.println(Text.of(Msg.ONLY_ZIP_CONFIG_PACKS_ARE_SUPPORTED_THE_GENERATOR_EMITS_ZIP_FOR_ARCHIVES_BASE));
        System.out.println(Text.of(Msg.UPDATE_INDEX_FILE_NOTE));
        System.out.println(Text.of(Msg.MANAGED_FILES_DROPPED_FROM_THE_NEW_MANIFEST_ARE_REMOVED_FILES_OUTSIDE_IT_ARE_UNTOUCHED));
        System.out.println(Text.of(Msg.LANGUAGE_NOTE));
        System.out.println(Text.of(Msg.UI_SCALE_NOTE));
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
