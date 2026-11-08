package com.coloryr.mml.serversync;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * -javaagent 入口。
 *
 * 用法：-javaagent:mml-server-sync.jar=http://example.com/server.json
 * agent 在游戏 main 之前执行同步；不再识别启动器，任何启动器下都照常同步。
 */
public final class Agent {
    private Agent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        // premain 从 -javaagent 指定的原始 jar 加载，此刻的类加载源就是原始 jar
        Updater.captureSelfJar();
        Path gameDir = gameDir();
        String manifest = agentArgs == null ? "" : agentArgs.trim();
        if (manifest.isEmpty()) {
            throw new RuntimeException(Text.of(Msg.MML_SERVER_SYNC_JAVAAGENT_ARGUMENT_IS_MISSING_THE_MANIFEST_URL));
        }
        int code;
        try {
            // 游戏在等 premain 返回，窗口不留、自更新不等
            code = Bootstrap.run(agentArgs(manifest, gameDir), 0, false);
        } catch (Throwable e) {
            throw new RuntimeException(Text.of(Msg.MML_SERVER_SYNC_UPDATE_FAILED_GAME_START_ABORTED), e);
        }
        if (code == 3) {
            // 玩家在失败提示框里选了「关闭游戏」：不是更新出错，别报成失败
            throw new RuntimeException(Text.of(Msg.USER_CLOSED_GAME));
        }
        if (code != 0) {
            throw new RuntimeException(Text.of(Msg.AGENT_EXIT_CODE, code));
        }
    }

    /**
     * 把 agent 的配置翻成命令行参数，交给 Runner 解析
     *
     * 自更新会加载新版本，入参统一走命令行，两个入口就只用维护一套解析逻辑。
     *
     * - `manifest`: 清单地址
     * - `gameDir`: 游戏目录
     *
     * # 返回值
     *
     * 等价的参数数组
     */
    private static String[] agentArgs(String manifest, Path gameDir) {
        Path state = stateDir(gameDir);
        List<String> args = new ArrayList<>();
        args.add("--manifest");
        args.add(manifest);
        args.add("--game-dir");
        args.add(gameDir.toString());
        args.add("--base-dir");
        args.add(Main.defaultBaseDir(gameDir).toString());
        args.add("--state-dir");
        args.add(state.toString());
        args.add("--log");
        args.add(logFile(state).toString());
        args.add("--threads");
        args.add(String.valueOf(threadsOf(System.getProperty("mml.serverSync.threads"))));
        if (truthy(System.getProperty("mml.serverSync.force"))
                || truthy(System.getenv("MML_SERVER_SYNC_FORCE"))) {
            args.add("--force");
        }
        // 清单拉不到时只记日志，别让服务器掉线锁死启动
        args.add("--lenient");
        // 启动场景窗口前就有人等着：默认允许自选模组勾选窗，无人值守时显式关掉
        if (!askEnabled()) {
            args.add("--no-ask");
        }
        if (!guiEnabled()) {
            args.add("--no-gui");
        } else {
            // 有进度窗口时，失败要问一句：重试、跳过本次更新，还是关闭游戏
            args.add("--ask-on-failure");
        }
        if (!updateEnabled()) {
            args.add("--no-update");
        }
        String skip = skipArgs();
        if (!skip.isEmpty()) {
            args.add("--skip");
            args.add(skip);
        }
        String index = updateIndex();
        if (!index.isEmpty()) {
            args.add("--update-index");
            args.add(index);
        }
        return args.toArray(new String[0]);
    }

    private static Path gameDir() {
        String property = System.getProperty("mml.serverSync.gameDir", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        String env = System.getenv("MML_SERVER_SYNC_GAME_DIR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 下载线程数，缺省或非法时用 16。 */
    private static int threadsOf(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 16;
        }
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    /** 是否显示进度窗口；-Dmml.serverSync.gui=false 或 MML_SERVER_SYNC_NO_GUI=1 关掉。 */
    private static boolean guiEnabled() {
        String value = System.getProperty("mml.serverSync.gui", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_GUI"));
        }
        return truthy(value);
    }

    /** 是否检查自更新；-Dmml.serverSync.update=false 或 MML_SERVER_SYNC_NO_UPDATE=1 关掉。 */
    private static boolean updateEnabled() {
        String value = System.getProperty("mml.serverSync.update", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_UPDATE"));
        }
        return truthy(value);
    }

    /** 是否允许弹自选模组勾选窗；-Dmml.serverSync.noAsk=true 或 MML_SERVER_SYNC_NO_ASK=1 关掉。 */
    private static boolean askEnabled() {
        String value = System.getProperty("mml.serverSync.noAsk", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_ASK"));
        }
        return !truthy(value);
    }

    /** 脚本 / 启动器传来的跳过列表：JVM 属性优先，其次环境变量；逗号分隔 */
    private static String skipArgs() {
        String property = System.getProperty("mml.serverSync.skip", "").trim();
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv("MML_SERVER_SYNC_SKIP");
        return env == null ? "" : env.trim();
    }

    /** 自更新索引地址；JVM 属性优先，其次环境变量，空串表示用默认地址。 */
    private static String updateIndex() {
        String property = System.getProperty("mml.serverSync.updateIndex", "").trim();
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv("MML_SERVER_SYNC_INDEX");
        return env == null ? "" : env.trim();
    }

    /** 日志文件路径：JVM 属性可覆盖，默认放在状态目录下。 */
    private static Path logFile(Path stateDir) {
        String property = System.getProperty("mml.serverSync.log", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return stateDir.resolve(Log.FILE_NAME);
    }

    private static Path stateDir(Path gameDir) {
        String property = System.getProperty("mml.serverSync.stateDir", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        String env = System.getenv("MML_SERVER_SYNC_STATE_DIR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return gameDir.resolve(".mml-server-sync");
    }

    /** FCL / PojavLauncher 等安卓启动器的环境特征；用于手机端界面缩放 */
    static boolean isAndroidLauncher() {
        // 启动器会把 os.version 写成 Android-15 / Android-16
        if (System.getProperty("os.version", "").toLowerCase(Locale.ROOT).contains("android")) {
            return true;
        }
        String[] keys = {"FCL_NATIVEDIR", "FCL_VERSION_CODE", "POJAV_NATIVEDIR",
                "POJAV_RENDERER", "MOD_ANDROID_RUNTIME", "DALVIK_JAVAVM", "ZALITH_VERSION_CODE"};
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(text) || "true".equals(text) || "yes".equals(text)
                || "mml".equals(text);
    }

}
