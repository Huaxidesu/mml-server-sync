package com.coloryr.mml.serversync;

import java.nio.file.Path;

/**
 * 独立服务器实例更新器入口。
 *
 * 入口只负责引导：查更新、挑要用的 jar，真正的同步在 {@link Runner} 里，
 * 可能来自 update 目录中的新版本。
 */
public final class Main {
    /** 工具版本号，启动日志、--version 与自更新比较都用它 */
    static final String VERSION = "1.9.0";

    private Main() {}

    public static void main(String[] args) {
        // -jar 模式的原始 jar 就是主类所在 jar；先记下，同步计划据此跳过自身条目
        Updater.captureSelfJar();
        // 独立运行时窗口多留一会儿，让用户看到结果；命令行模式等自更新收尾
        int code = Bootstrap.run(args, 900, true);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * 默认基础包目录
     *
     * - `gameDir`: 游戏目录
     *
     * # 返回值
     *
     * 游戏目录是 .minecraft 时返回它的父目录，否则就是游戏目录本身
     */
    static Path defaultBaseDir(Path gameDir) {
        Path name = gameDir.getFileName();
        if (name != null && ".minecraft".equalsIgnoreCase(name.toString())
                && gameDir.getParent() != null) {
            return gameDir.getParent();
        }
        return gameDir;
    }
}
