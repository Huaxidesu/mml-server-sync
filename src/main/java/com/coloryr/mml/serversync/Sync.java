package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.coloryr.mml.serversync.host.Progress;

/** 一次服务器实例更新。 */
final class Sync {
    /** 禁用后缀：与 MMC / HMCL 一致 */
    private static final String DISABLED_SUFFIX = SyncRules.DISABLED_SUFFIX;
    /** 旧版禁用后缀，读取时仍认 */
    private static final String LEGACY_DISABLE_SUFFIX = SyncRules.LEGACY_DISABLE_SUFFIX;

    private final Options options;
    private final Downloader downloader;
    private final Path tempDir;
    /** 进度窗口；没有窗口时是空实现 */
    private final Progress ui;

    /** 远程清单，解析完成后供「自选模组」弹窗即时组装行数据 */
    private volatile Manifest manifest;
    /** 自选模组的上次选择状态 */
    private OptionalState.Loaded optionalState = new OptionalState.Loaded(true);
    /** 本次的自选模组勾选：路径 → 是否启用；事件线程（弹窗）与同步线程都会读写 */
    private final Map<String, Boolean> selections = new ConcurrentHashMap<>();
    /** 发布端写的自选模组简介：路径 → 文字；取不到时为空 */
    private Map<String, String> optionalNotes = Collections.emptyMap();
    /** 打开中的自选模组弹窗；玩家确认后清空 */
    private final AtomicReference<PendingChoice> pendingDialog = new AtomicReference<>();
    /** 本轮最近一次确认过的弹窗结果；弹窗线程写入，收尾时读取，null 表示还没有玩家确认过 */
    private volatile OptionalModsDialog.Result lastChoice;
    /** 玩家是否勾过「以后不再显示」：没勾时每次启动都弹窗，勾了只在有新增或变化时弹 */
    private boolean promptSuppressed;
    /** 本轮由发布端删除列表纳入的路径，用于区分「废弃删除」与「旧清单差异删除」 */
    private final Set<String> retiredPaths = new java.util.HashSet<>();
    /** 本轮的同步规则（删除列表与屏蔽规则）；未读到旁文件时为空规则 */
    private SyncRules rules = SyncRules.empty();
    /**
     * 旁文件是否成功读取。默认 true：指纹命中与包版本未变两条快速路径不读旁文件，
     * 但它们只做自选模组改名，不能因此停掉旧副本清理。只有读取失败才置 false，
     * 此时本轮不执行任何删除，也不清空归档目录。
     */
    private boolean rulesAvailable = true;
    /**
     * 本轮是否真的读到过旁文件。指纹命中与包版本未变两条快速路径不读旁文件，
     * 此时屏蔽范围未知：归位只改名，不清理重复副本。
     */
    private boolean rulesFetched;
    /** 本轮按清单改名的文件数 */
    private int renamedCount;
    /** 本轮内容一致、跳过下载的清单条目数 */
    private int skippedCount;
    /** 本轮删除的文件数（含禁用变体） */
    private int removedCount;

    Sync(Options options, Progress ui) {
        this.options = options;
        this.downloader = new Downloader(options.apiKey, options.quiet);
        this.tempDir = options.stateDir.resolve("tmp");
        this.ui = ui == null ? Progress.NONE : ui;
    }

    /** 打开中的自选模组弹窗：玩家确认后 latch 归零 */
    private static final class PendingChoice {
        final CountDownLatch done = new CountDownLatch(1);

        /** 等玩家确认；结果由 {@link #lastChoice} 取 */
        void await() throws InterruptedException {
            done.await();
        }
    }

    /**
     * 跑一次同步
     *
     * # 返回值
     *
     * 一句话结果，给进度窗口与日志用
     */
    String run() throws IOException {
        try {
            return runInner();
        } catch (IOException e) {
            // 游戏模式（javaagent 会带 --lenient）：服务器掉线、繁忙、传输中断都只跳过本次同步。
            // 这里不能把异常抛给 premain —— 那会让 JVM 直接终止，游戏根本起不来。
            // 开了 --ask-on-failure 时交给 Runner 弹窗，由玩家决定重试还是跳过。
            if (!options.lenient || options.askOnFailure) {
                throw e;
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.error(Text.of(Msg.SYNC_INTERRUPTED_SKIPPING_THIS_SYNC, reason));
            return Text.of(Msg.SYNC_INTERRUPTED_SYNC_SKIPPED);
        }
    }

    private String runInner() throws IOException {
        String manifestUrl;
        byte[] data;
        Manifest current;
        // 「以后不再显示」的标记先读：两条不下载任何文件的提前返回也要按它决定弹不弹
        promptSuppressed = OptionalState.promptSuppressed(options.stateDir);
        try {
            manifestUrl = toUrl(options.manifest);
            ui.stage(Text.of(Msg.FETCHING_MANIFEST_FINGERPRINT));
            // 先比清单指纹：发布目录里有 server.json.sha1 且没变时，整份清单都不用下
            if (!options.force && manifestUnchanged(manifestUrl)) {
                String summary = Text.of(Msg.MANIFEST_UNCHANGED_SKIPPING_SYNC);
                // 指纹一致说明本地副本与远端字节相同，弹窗直接拿本地的组装，不重下整份清单
                String prompted = promptFromLocalManifest(manifestUrl);
                return prompted == null ? summary : prompted;
            }
            ui.stage(Text.of(Msg.DOWNLOADING_MANIFEST));
            // 清单可能要下几十秒：进度条转不确定状态，接收进度回传到窗口
            ui.total(0);
            data = downloader.fetch(manifestUrl, null, (done, total) -> ui.step(0, total > 0
                    ? Text.of(Msg.MANIFEST_PROGRESS_TOTAL, mb(done), mb(total))
                    : Text.of(Msg.MANIFEST_PROGRESS, mb(done))));
            current = Manifest.parse(data);
            current.resolveUrls(manifestUrl);
        } catch (IOException e) {
            // 清单没拉到等于一个文件都没动：游戏模式只记日志，别让服务器掉线锁死启动
            // （--ask-on-failure 时抛给 Runner 弹窗，让玩家选重试还是跳过）
            if (!options.lenient || options.askOnFailure) {
                throw e;
            }
            Log.error(Text.of(Msg.MANIFEST_FETCH_FAILED_SKIPPING_THIS_SYNC) + e.getMessage());
            return Text.of(Msg.MANIFEST_FETCH_FAILED_SYNC_SKIPPED);
        }
        manifest = current;
        ui.title((current.name.isEmpty() ? Text.of(Msg.SERVER_INSTANCE) : current.name)
                + " · " + packVersionOf(current));

        List<Manifest.FileEntry> changedOptionals = prepareOptionals(current);

        // 「自选模组」入口常驻：清单就绪即可点，弹窗按当前状态即时组装。
        // 按钮按文字在窗口树里定位后直接挂监听：从 update/ 包运行时类加载器分裂，
        // 无法用具体窗口类型接线，扫描方式两种运行形态通用。
        if (canAsk()) {
            optionalNotes = fetchOptionalNotes(manifestUrl);
            javax.swing.JButton button = findOptionalButton();
            if (button != null) {
                button.setEnabled(true);
                button.addActionListener(event -> openManageDialog());
            }
        }

        Manifest old = loadLocalManifest();
        if (old == null) {
            info(Text.of(Msg.NO_LOCAL_RECORD_VERSION, packVersionOf(current)));
        } else {
            info(Text.of(Msg.LOCAL_REMOTE_VERSION, packVersionOf(old), packVersionOf(current)));
        }
        if (!options.force && sameVersion(old, current)) {
            info(Text.of(Msg.PACK_VERSION_UNCHANGED_DETAIL, current.packVersion));
            String summary = Text.of(Msg.PACK_VERSION_UNCHANGED_SKIPPING_SYNC);
            // 版本没变照样要弹：玩家每次启动都该有一次改选的机会，选完当场归位。
            // 清单可能已经改了（发布端漏了递增版本），所以还要按 Optional 标记的差异归位。
            String prompted = promptOnUnchanged(current, changedOptionals.size(), manifestUrl,
                    changedOptionalFlags(old, current));
            return prompted == null ? summary : prompted;
        }

        // 两条提前返回都不读旁文件：删除列表与屏蔽规则只在真正执行同步时生效
        rules = fetchSyncRules(manifestUrl);

        ui.stage(Text.of(Msg.CHECKING_LOCAL_FILES));
        validate(current);
        if (old != null) {
            validate(old);
        }
        ui.stage(Text.of(Msg.COMPARING_CHANGES));

        // 弹窗就绪即开：同步继续在后台跑，收尾时再来等确认结果
        if (shouldPrompt(changedOptionals.size())) {
            openDialog(buildRows(current),
                    promptHeader(changedOptionals.size(), countOptionals(current)), promptSuppressed);
        } else if (promptSuppressed && canAsk()) {
            info(Text.of(Msg.PROMPT_SUPPRESSED_HINT));
        }

        List<String> removedDirs = new ArrayList<>();
        Set<String> removedFiles = new java.util.HashSet<>();
        String selfJar = selfJarRelative();
        planRemovals(old, current, removedFiles, removedDirs, selfJar);
        if (options.dryRun) {
            printOptionalPlan(current);
            printPlan(current, old, removedFiles, removedDirs);
            return Text.of(Msg.PLAN_ONLY_NO_FILES_CHANGED);
        }
        checkCancelled();
        ui.stage(Text.of(Msg.CLEANING_OLD_FILES));
        if (!options.noDelete) {
            applyRemovals(removedFiles, removedDirs);
        } else {
            info(Text.of(Msg.NO_DELETE_SKIPPED, removedFiles.size(), removedDirs.size()));
        }

        boolean needBase = current.base != null && (old == null || old.base == null);
        List<PlannedFile> files = changedFiles(current, selfJar);
        List<ArchivePlan> archives = archivePlans(old, current, removedDirs);
        int archiveCount = 0;
        for (ArchivePlan plan : archives) {
            if (!plan.skip) {
                archiveCount++;
            }
        }
        int total = files.size() + archiveCount + (needBase ? 1 : 0);
        info(Text.of(Msg.NEED_TO_HANDLE_ITEMS, total, files.size(), archiveCount, needBase ? 1 : 0));
        // 下载一步、解压一步，进度条按两步走
        ui.total(total + archiveCount + (needBase ? 1 : 0));

        Files.createDirectories(tempDir);
        Path baseTemp = null;
        List<DownloadTask> tasks = new ArrayList<>();
        if (needBase) {
            Manifest.BaseEntry base = current.base;
            baseTemp = tempDir.resolve(safeName(base.file));
            tasks.add(new DownloadTask(base.url, baseTemp, base.sha1, base.sha256, null));
        }

        for (PlannedFile planned : files) {
            tasks.add(new DownloadTask(null, planned.target, planned.item.sha1, planned.item.sha256,
                    planned.item));
        }

        List<ArchivePlan> downloaded = new ArrayList<>();
        int archiveIndex = 0;
        for (ArchivePlan plan : archives) {
            if (plan.skip) {
                continue;
            }
            String url = plan.item.url.trim();
            if (url.isEmpty()) {
                throw new IOException(Text.of(Msg.CONFIG_ARCHIVE_HAS_NO_DOWNLOAD_URL) + plan.item.file);
            }
            Path temp = tempDir.resolve("archive-" + (archiveIndex++) + "-" + safeName(plan.item.file));
            tasks.add(new DownloadTask(url, temp, plan.item.sha1, plan.item.sha256, null));
            downloaded.add(new ArchivePlan(plan.item, temp, false));
        }

        ui.stage(Text.of(Msg.DOWNLOADING_FILES));
        int done = downloadAll(tasks, total);

        if (baseTemp != null) {
            checkCancelled();
            ui.stage(Text.of(Msg.EXTRACTING_BASE_PACK));
            Fs.extractZip(baseTemp, options.baseDir, true);
            Files.deleteIfExists(baseTemp);
            info(Text.of(Msg.BASE_EXTRACTED_TO, options.baseDir));
            ui.step(++done, current.base.file);
        }

        downloaded.sort(Comparator.comparingInt((ArchivePlan plan) -> depth(plan.item.dir)));
        if (!downloaded.isEmpty()) {
            ui.stage(Text.of(Msg.EXTRACTING_ARCHIVES));
        }
        for (ArchivePlan plan : downloaded) {
            checkCancelled();
            Path target = Fs.resolveSafe(options.gameDir, plan.item.dir);
            if (plan.item.deleteOld && Fs.isEmptyZip(plan.temp)) {
                // 空包 + 覆盖 = 显式删除该目录（发布端用它替代「删条目」）。
                // 启用屏蔽规则时整目录删除无法保证「屏蔽项不动」，按约定不执行。
                if (canRemoveDirectory(plan.item.dir)) {
                    Fs.deleteDirectory(target);
                    info(Text.of(Msg.EMPTY_ARCHIVE_REMOVES_DIR, plan.item.dir));
                }
            } else {
                if (plan.item.deleteOld) {
                    clearArchiveDirectory(plan.item.dir, target);
                }
                // 归档里的条目同样不能覆盖屏蔽项：整目录覆盖时也要让屏蔽规则生效
                Fs.extractZip(plan.temp, target, false,
                        name -> rules.isIgnored(plan.item.dir + "/" + name));
                info(Text.of(Msg.EXTRACTING_CONFIG_TARGET, plan.item.dir));
            }
            Files.deleteIfExists(plan.temp);
            ui.step(++done, plan.item.dir);
        }

        checkCancelled();
        // 自选模组收尾：等后台弹窗的确认结果，然后按勾选把文件归位，之后才写状态、放行启动
        applyOptionalChoices(current);

        ui.stage(Text.of(Msg.SAVING_STATE));
        saveState(data);
        OptionalState.save(options.stateDir, OptionalState.entries(selections, hashesOf(current)));
        // 收尾计数：玩家反馈「没下载某个模组」时，看这四类计数就能判断走了哪条路径
        info(Text.of(Msg.SYNC_SUMMARY, renamedCount, files.size() + archiveCount + (needBase ? 1 : 0),
                skippedCount, removedCount));
        return Text.of(Msg.SYNC_COMPLETE_VERSION, current.packVersion);
    }

    /**
     * 自选模组收尾：仍开着的弹窗在这里等玩家确认；结果并进勾选后按名字归位。
     * 必须发生在 saveState 之前，否则中断后指纹命中会跳过收尾，改名就再也不会执行。
     */
    private void applyOptionalChoices(Manifest current) throws IOException {
        PendingChoice pending = pendingDialog.get();
        if (pending != null) {
            ui.stage(Text.of(Msg.WAITING_FOR_OPTIONAL_MOD_SELECTION));
            try {
                pending.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(Text.of(Msg.DOWNLOAD_INTERRUPTED), e);
            }
        }
        checkCancelled();
        consumeChoice();
        applySelections(current, null);
    }

    /**
     * 把玩家确认的结果并进勾选，并按需要记住「以后不再显示」
     *
     * 只并入弹窗展示过的行：部分行弹窗（如仅新增条目）确认时，未展示行的选择保持原状。
     *
     * # 返回值
     *
     * 选择或「以后不再显示」的标记有变化时返回 true
     */
    private boolean consumeChoice() {
        if (lastChoice == null) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Boolean> entry : lastChoice.selections.entrySet()) {
            if (!entry.getValue().equals(selections.put(entry.getKey(), entry.getValue()))) {
                changed = true;
            }
        }
        if (lastChoice.suppressPrompt != promptSuppressed) {
            try {
                OptionalState.savePromptSuppressed(options.stateDir, lastChoice.suppressPrompt);
                promptSuppressed = lastChoice.suppressPrompt;
                changed = true;
            } catch (IOException e) {
                // 标记写不进去只是下次还会弹，不值得把整次同步判失败
                Log.error(Text.of(Msg.FAILED_TO_SAVE_THE_OPTIONAL_PROMPT_STATE) + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * 按清单把文件归位，并把启用 / 禁用条数写进日志
     *
     * - `current`: 清单
     * - `onlyPaths`: 只处理这些路径（规范化键）；null 表示整份清单
     *
     * # 返回值
     *
     * 本轮改名的文件数
     */
    private int applySelections(Manifest current, Set<String> onlyPaths) throws IOException {
        ui.stage(Text.of(Msg.OPTIONAL_APPLY_STAGE));
        int renamed = reconcileManagedFiles(current, onlyPaths);
        int enabled = 0;
        int disabled = 0;
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            if (Boolean.TRUE.equals(selections.get(item.path))) {
                enabled++;
            } else {
                disabled++;
            }
        }
        info(Text.of(Msg.OPTIONAL_APPLY_COUNT, enabled, disabled));
        return renamed;
    }

    /**
     * 按清单把文件归位：必装条目一律启用，自选条目按勾选启用或加 .disabled。
     * 内容在下载阶段已就位，这里通常只是改名；同一模组的其它变体清掉，避免同名双份。
     *
     * 必装条目同样参与归位，是本次功能的核心：发布端把自选模组改成必装时，
     * 玩家本地只有 `.disabled` 副本，内容与清单一致就不必重新下载，改名即可启用。
     *
     * 归位在下载之后执行：内容不一致的条目此时已被下载覆盖到目标路径，
     * 因此「先改名再下载」导致目标被误判为已存在的情况不会发生。
     *
     * - `current`: 清单
     * - `onlyPaths`: 只处理这些路径（规范化键）；null 表示整份清单
     *
     * # 返回值
     *
     * 本轮改名的文件数
     */
    private int reconcileManagedFiles(Manifest current, Set<String> onlyPaths) throws IOException {
        if (onlyPaths != null && onlyPaths.isEmpty()) {
            return 0;
        }
        String selfKey = Fs.pathKey(selfJarRelative());
        List<String> archiveDirs = archiveDirsOf(current);
        // 旁文件没读到时不清理重复副本：无法确认哪些路径受屏蔽保护
        boolean deleteStale = !options.noDelete && rulesFetched && rulesAvailable;
        int renamed = 0;
        for (Manifest.FileEntry item : current.files) {
            if (rules.isIgnored(item.path)) {
                continue;
            }
            String key = Fs.pathKey(item.path);
            if (onlyPaths != null && !onlyPaths.contains(key)) {
                continue;
            }
            if (!selfKey.isEmpty() && key.equals(selfKey)) {
                continue;
            }
            if (insideArchive(key, archiveDirs)) {
                continue;
            }
            Path target;
            try {
                target = Fs.resolveSafe(options.gameDir, item.path);
            } catch (IOException | RuntimeException e) {
                info(Text.of(Msg.MANAGED_BAD_PATH, item.path));
                continue;
            }
            // 必装条目强制启用；自选条目沿用玩家勾选
            boolean selected = !item.optional || Boolean.TRUE.equals(selections.get(item.path));
            Path desired = selected ? target : withSuffix(target, DISABLED_SUFFIX);
            Path existing = existingVariant(target);
            if (existing == null) {
                continue;
            }
            if (!existing.equals(desired)) {
                // 内容对不上时不得抢在下载之前改名：否则下载计划会把目标当成已存在
                if (!Downloader.matches(existing, item.sha1, item.sha256)) {
                    continue;
                }
                try {
                    moveReplace(existing, desired);
                    renamed++;
                    renamedCount++;
                    info(Text.of(Msg.MANAGED_RENAMED, existing.getFileName().toString(),
                            desired.getFileName().toString()));
                } catch (IOException e) {
                    // 文件被占用、权限不足：保持原状，下一轮重试，不因此中断启动
                    info(Text.of(Msg.MANAGED_RENAME_FAILED, existing.getFileName().toString(),
                            desired.getFileName().toString(), describe(e)));
                    continue;
                }
            }
            if (deleteStale) {
                deleteOtherVariants(target, desired);
            }
        }
        return renamed;
    }

    /**
     * 删掉同一路径的其它变体，只留 `desired`
     *
     * 同一模组同时存在 `x.jar` 与 `x.jar.disabled` 时，Forge 只会加载其中一个，
     * 留两份既占磁盘也让排查变复杂。旧后缀 `.disable` 的残留一并清掉。
     *
     * - `target`: 清单里的原始路径
     * - `desired`: 归位后的路径
     */
    private void deleteOtherVariants(Path target, Path desired) {
        for (String suffix : new String[]{"", DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            Path candidate = suffix.isEmpty() ? target : withSuffix(target, suffix);
            if (candidate.equals(desired) || !Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                Files.deleteIfExists(candidate);
                info(Text.of(Msg.MANAGED_STALE_REMOVED, candidate.getFileName().toString()));
            } catch (IOException e) {
                info(Text.of(Msg.MANAGED_STALE_FAILED, candidate.getFileName().toString(),
                        describe(e)));
            }
        }
    }

    /** 异常的一句话说明：没有 message 时退回类型名 */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 按钮回调：弹管理窗；已有弹窗开着、还没解析到清单或 dry-run 时忽略 */
    private void openManageDialog() {
        Manifest current = manifest;
        if (options.dryRun || current == null || pendingDialog.get() != null) {
            return;
        }
        openDialog(buildRows(current), Text.of(Msg.HEADER_MANAGE, countOptionals(current)),
                promptSuppressed);
    }

    /**
     * 打开勾选窗，返回可用于等待的句柄
     *
     * 窗口在事件线程上开，本方法立即返回；调用方要等玩家确认就 await 返回的句柄。
     * 已有弹窗开着时返回 null——同一时刻只允许一扇。
     *
     * - `rows`: 要列出的条目
     * - `header`: 表格上方的说明文字
     * - `rememberChecked`: 「以后不再显示」的初始勾选状态
     *
     * # 返回值
     *
     * 本次弹窗的句柄；已有弹窗开着时返回 null
     */
    private PendingChoice openDialog(List<OptionalModsDialog.Row> rows, String header,
                                     boolean rememberChecked) {
        PendingChoice pending = new PendingChoice();
        if (!pendingDialog.compareAndSet(null, pending)) {
            return null;
        }
        try {
            javax.swing.JFrame parent = ownerFrame();
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    lastChoice = OptionalModsDialog.askForSelection(parent, rows, header,
                            rememberChecked);
                } finally {
                    pending.done.countDown();
                    pendingDialog.compareAndSet(pending, null);
                }
            });
        } catch (Throwable e) {
            // 弹窗打不开（没有显示服务器、AWT 初始化失败）：按没弹窗继续同步，不能中断更新
            System.setProperty(ProgressWindow.GUI_FAILED_KEY, "true");
            pending.done.countDown();
            pendingDialog.compareAndSet(pending, null);
            String message = e.getMessage();
            info(Text.of(Msg.FAILED_TO_OPEN_THE_OPTIONAL_MOD_DIALOG,
                    message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message));
        }
        return pending;
    }

    /**
     * 读清单里的可选条目与上次的选择，填好本次的勾选
     *
     * @param current 清单
     *
     * # 返回值
     *
     * 新增或内容有变化的可选条目；非空说明清单这边动过自选模组
     */
    private List<Manifest.FileEntry> prepareOptionals(Manifest current) {
        OptionalState.Loaded state = OptionalState.load(options.stateDir);
        optionalState = state;
        List<Manifest.FileEntry> changed = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            Boolean recorded = state.selected(item.path);
            if (recorded == null) {
                // 换版本的同一模组路径变了：沿用同族上一次的选择，别把玩家的停用改成启用
                recorded = state.inherited(item.path);
            }
            boolean selected;
            if (options.skip.contains(item.path)) {
                selected = false;
            } else if (recorded != null) {
                selected = recorded;
            } else {
                selected = canAsk();
            }
            selections.put(item.path, selected);
            if (!state.hashOf(item.path).equalsIgnoreCase(contentHash(item))) {
                changed.add(item);
            }
        }
        return changed;
    }

    /**
     * 该不该弹自选模组窗
     *
     * 没勾「以后不再显示」时每次启动都弹；勾了就只在有新增或内容变化时弹——
     * 否则以后新增的自选模组玩家永远没机会启用。
     *
     * @param changedCount 新增或内容变化的可选条目数
     */
    private boolean shouldPrompt(int changedCount) {
        return !options.dryRun && options.skip.isEmpty() && canAsk()
                && (!promptSuppressed || changedCount > 0);
    }

    /** 弹窗标题：首次同步 / 每次启动 / 有新增或变化三种场景 */
    private String promptHeader(int changedCount, int total) {
        if (optionalState.firstRun) {
            return Text.of(Msg.HEADER_FIRST_RUN);
        }
        if (promptSuppressed) {
            return Text.of(Msg.HEADER_CHANGES, changedCount);
        }
        return Text.of(Msg.HEADER_EVERY_START, total);
    }

    /**
     * 指纹命中时的弹窗：本地清单副本与远端字节相同，直接拿来组装行数据
     *
     * 已勾「以后不再显示」时连副本都不必解析——清单没变就不会有新增或变化，
     * 这条快速路径保持一次请求、零解析。
     *
     * @param manifestUrl 清单地址，用于取发布端写的简介
     *
     * # 返回值
     *
     * 玩家改过选择时的一句话结果；不弹或没改动时为 null
     */
    private String promptFromLocalManifest(String manifestUrl) {
        if (!shouldPrompt(0)) {
            return null;
        }
        Manifest local = loadLocalManifest();
        if (local == null) {
            return null;
        }
        ui.title((local.name.isEmpty() ? Text.of(Msg.SERVER_INSTANCE) : local.name)
                + " · " + packVersionOf(local));
        List<Manifest.FileEntry> changed = prepareOptionals(local);
        // 指纹一致说明清单字节没变，Optional 标记不可能变化：无需按差异归位
        return promptOnUnchanged(local, changed.size(), manifestUrl, Collections.emptySet());
    }

    /**
     * 没有文件要更新时弹窗，选完当场按勾选归位
     *
     * 这条路径不下载任何文件，归位只是改文件名；选择与标记写完就生效，
     * 不必等下一次真正的同步。归位本身出错只记日志：这条路径一个文件都没下载过，
     * 不该因为改名失败拦住启动，下次启动会照原样重试。
     *
     * 归位不只服务于勾选变化：发布端把自选模组改成必装、却没递增 `PackVersion` 时，
     * 本地停用的副本也要改回启用名，否则玩家要等到下一次版本递增才生效。
     * 这类归位只处理两版清单之间 `Optional` 标记变过的路径，避免每次启动扫描整份清单。
     *
     * @param current      清单，用于组装行与归位
     * @param changedCount 新增或内容变化的可选条目数
     * @param manifestUrl  清单地址，用于取发布端写的简介
     * @param migrated     两版清单之间 Optional 标记变过的路径（规范化键）
     *
     * # 返回值
     *
     * 玩家改过选择或发生过归位时的一句话结果；什么都没做时为 null
     */
    private String promptOnUnchanged(Manifest current, int changedCount, String manifestUrl,
                                     Set<String> migrated) {
        boolean changed = false;
        if (shouldPrompt(changedCount)) {
            optionalNotes = fetchOptionalNotes(manifestUrl);
            ui.stage(Text.of(Msg.WAITING_FOR_OPTIONAL_MOD_SELECTION));
            PendingChoice pending = openDialog(buildRows(current),
                    promptHeader(changedCount, countOptionals(current)), promptSuppressed);
            if (pending == null) {
                return null;
            }
            try {
                pending.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            changed = consumeChoice();
        }
        if (!changed && migrated.isEmpty()) {
            return null;
        }
        int renamed = 0;
        try {
            // 玩家改过选择时整份清单归位（沿用原有行为）；否则只处理标记变过的路径
            renamed = applySelections(current, changed ? null : migrated);
            OptionalState.save(options.stateDir, OptionalState.entries(selections, hashesOf(current)));
        } catch (IOException e) {
            Log.error(Text.of(Msg.SYNC_FAILED_2) + e.getMessage());
        }
        if (changed) {
            return Text.of(Msg.OPTIONAL_CHOICES_SAVED);
        }
        return renamed > 0 ? Text.of(Msg.MANAGED_REALIGNED, renamed) : null;
    }

    /** 弹窗行数据：状态区分新增 / 需更新 / 已同步，并带当前启用状态后缀 */
    private List<OptionalModsDialog.Row> buildRows(Manifest current) {
        List<OptionalModsDialog.Row> rows = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            Path existing = existingVariantQuiet(item.path);
            boolean isNew = optionalState.hashOf(item.path).isEmpty();
            boolean contentStale = existing == null
                    || !Downloader.matches(existing, item.sha1, item.sha256)
                    || !optionalState.hashOf(item.path).equalsIgnoreCase(contentHash(item));
            String status = isNew ? Text.of(Msg.ADDED)
                    : contentStale ? Text.of(Msg.STATUS_NEEDS_UPDATE) : Text.of(Msg.STATUS_UP_TO_DATE);
            if (existing != null) {
                status += isDisabled(existing) ? Text.of(Msg.DISABLED) : Text.of(Msg.ENABLED);
            }
            rows.add(new OptionalModsDialog.Row(item.path, item.fileName(),
                    optionalNotes.get(item.path), status,
                    Boolean.TRUE.equals(selections.get(item.path))));
        }
        return rows;
    }

    /**
     * 取发布端写的自选模组简介：清单地址加 .notes.json，取不到就是没有
     *
     * - `manifestUrl`: 清单地址
     *
     * # 返回值
     *
     * 路径 → 简介文字；文件不存在或格式不对时为空表
     */
    private Map<String, String> fetchOptionalNotes(String manifestUrl) {
        try {
            Map<String, Object> root = Json.map(Json.parse(
                    downloader.fetchQuiet(manifestUrl + ".notes.json")));
            Map<String, String> out = new HashMap<>();
            if (root != null) {
                for (Map.Entry<String, Object> entry : root.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        out.put(entry.getKey(), (String) entry.getValue());
                    }
                }
            }
            return out;
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    /** 自选模组的勾选条目 → 状态文件记录 */
    private static Map<String, String> hashesOf(Manifest current) {
        Map<String, String> out = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            if (item.optional) {
                out.put(item.path, contentHash(item));
            }
        }
        return out;
    }

    private static int countOptionals(Manifest current) {
        int count = 0;
        for (Manifest.FileEntry item : current.files) {
            if (item.optional) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清单是否没变：先取 `<清单>.sha1`，与本地清单的 SHA1 比
     *
     * 发布目录没放 .sha1、取不到、或本地还没有清单时都按"变了"处理，照旧整份下载。
     * 口径与启动器的 server_pack_update 一致。
     *
     * - `manifestUrl`: 清单地址
     *
     * # 返回值
     *
     * 远端指纹与本地清单一致时返回 true
     */
    private boolean manifestUnchanged(String manifestUrl) {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            return false;
        }
        String remote = remoteSha1(manifestUrl);
        if (remote.isEmpty()) {
            return false;
        }
        String local = localManifestSha1();
        if (local.isEmpty() || !local.equalsIgnoreCase(remote)) {
            return false;
        }
        info(Text.of(Msg.MANIFEST_SHA1_UNCHANGED, remote.substring(0, 8)));
        return true;
    }

    /** 取 `<清单>.sha1`，只认纯哈希；取不到或内容不像哈希时返回空串 */
    private String remoteSha1(String manifestUrl) {
        try {
            String text = new String(downloader.fetchQuiet(manifestUrl + ".sha1"),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int space = text.indexOf(' ');
            if (space > 0) {
                // 有些工具会写成 "哈希  文件名"
                text = text.substring(0, space).trim();
            }
            return text.matches("[0-9a-fA-F]{40}") ? text : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 本地清单的 SHA1：状态文件优先，其次基础包目录里的 server.json */
    private String localManifestSha1() {
        Path state = options.stateDir.resolve("last-manifest.json");
        Path fallback = options.baseDir.resolve("server.json");
        for (Path path : new Path[]{state, fallback}) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String hash = Downloader.hash(path, "SHA-1");
            if (!hash.isEmpty()) {
                return hash;
            }
        }
        return "";
    }

    /**
     * 用户在进度窗上按了取消：到安全点就停
     *
     * 系统属性由进度窗写：skip 跳过本次更新（退出码 0），end 结束游戏（退出码 1）。
     */
    private static void checkCancelled() {
        Cancels.check();
    }

    /** 清单的包版本，空串按「未标注」显示。 */
    private static String packVersionOf(Manifest manifest) {
        String version = manifest.packVersion == null ? "" : manifest.packVersion.trim();
        return version.isEmpty() ? Text.of(Msg.NOT_SPECIFIED) : version;
    }

    /**
     * 本地留存的上一份清单：状态目录里的副本优先，其次基础包里的 server.json
     *
     * 指纹命中时它和远端字节相同，可以直接当远端清单用。
     *
     * # 返回值
     *
     * 解析出的旧清单；两份都读不出来时返回 null
     */
    private Manifest loadLocalManifest() {
        Path state = options.stateDir.resolve("last-manifest.json");
        Path fallback = options.baseDir.resolve("server.json");
        for (Path path : new Path[]{state, fallback}) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                byte[] data = Files.readAllBytes(path);
                Manifest old = Manifest.parse(data);
                old.resolveUrls(toUrl(options.manifest));
                return old;
            } catch (IOException e) {
                info(Text.of(Msg.IGNORING_OLD_MANIFEST, path, e.getMessage()));
            }
        }
        return null;
    }

    private boolean sameVersion(Manifest old, Manifest current) {
        if (old == null) {
            return false;
        }
        String a = old.packVersion.trim();
        String b = current.packVersion.trim();
        return !b.isEmpty() && a.equals(b);
    }

    /**
     * 两版清单之间 `Optional` 标记变过的路径
     *
     * 发布端把自选模组改成必装（或反向）时，玩家本地文件的状态要跟着归位。
     * 包版本未变的路径上不下载任何文件，因此只对这批路径执行归位，
     * 避免每次启动都去 stat 整份清单的近两万个条目。
     *
     * - `old`: 本地留存的上一份清单
     * - `current`: 本次清单
     *
     * # 返回值
     *
     * 规范化路径键集合；没有旧清单时为空
     */
    private static Set<String> changedOptionalFlags(Manifest old, Manifest current) {
        Set<String> out = new java.util.HashSet<>();
        if (old == null) {
            return out;
        }
        Map<String, Boolean> before = new HashMap<>();
        for (Manifest.FileEntry item : old.files) {
            before.put(Fs.pathKey(item.path), item.optional);
        }
        for (Manifest.FileEntry item : current.files) {
            Boolean was = before.get(Fs.pathKey(item.path));
            if (was != null && was.booleanValue() != item.optional) {
                out.add(Fs.pathKey(item.path));
            }
        }
        return out;
    }

    /**
     * 取清单同目录下的同步规则旁文件。
     *
     * 读到且能解析：按规则执行。其余任何情况（404、超时、5xx、返回网页、JSON 坏）
     * 都按「规则不可用」处理：不阻断同步，但本轮不执行任何文件删除，
     * 避免在无法确认屏蔽范围时误删玩家文件。
     *
     * 归档解压不受这条门禁约束：`Over=true` 是「整包替换目录」的语义，
     * 与逐文件删除无关，见 {@link #clearArchiveDirectory}。
     */
    private SyncRules fetchSyncRules(String manifestUrl) {
        try {
            SyncRules loaded = SyncRules.parse(downloader.fetchQuiet(manifestUrl + ".sync.json"));
            rulesFetched = true;
            rulesAvailable = true;
            info(Text.of(Msg.SYNC_RULES_LOADED, loaded.removed.size(), loaded.ignoreCount()));
            return loaded;
        } catch (IOException | RuntimeException e) {
            rulesFetched = true;
            rulesAvailable = false;
            String reason = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
            info(Text.of(Msg.SYNC_RULES_UNAVAILABLE, reason));
            info(Text.of(Msg.SIDECAR_UNAVAILABLE_NO_DELETE));
            return SyncRules.empty();
        }
    }

    private void validate(Manifest manifest) throws IOException {
        for (Manifest.FileEntry item : manifest.files) {
            Fs.resolveSafe(options.gameDir, item.path);
        }
        for (Manifest.ArchiveEntry item : manifest.archives) {
            Fs.resolveSafe(tempDir, item.file);
            Fs.resolveSafe(options.gameDir, item.dir);
        }
        if (manifest.base != null) {
            Fs.resolveSafe(tempDir, manifest.base.file);
        }
    }

    private void planRemovals(Manifest old, Manifest current, Set<String> files, List<String> dirs,
                              String selfJar) {
        retiredPaths.clear();
        // 旁文件没读到：无法确认屏蔽范围，本轮一条都不删（下载照常）
        if (!rulesAvailable) {
            return;
        }
        Set<String> live = new java.util.HashSet<>();
        for (Manifest.FileEntry item : current.files) {
            live.add(Fs.pathKey(item.path));
        }
        // 归档目录内的文件由压缩包负责：条目已从清单移除、但归档未重解压时删了不会补回来
        List<String> archiveDirs = archiveDirsOf(current);
        String selfKey = Fs.pathKey(selfJar);

        // 删除列表与本地同步记录无关：首次同步、跨版本升级都能一次收敛。
        // 清单里仍存在的路径绝不删（含其 .disabled 副本），避免大小写或禁用后缀造成「刚下载就被删」。
        int retired = 0;
        int liveHit = 0;
        int ignoreHit = 0;
        int archiveHit = 0;
        Set<String> queued = new java.util.HashSet<>();
        for (String path : rules.removed) {
            // 删除列表可以写启用名，也可以写禁用变体：统一按去掉后缀的路径处理，
            // 一条规则即可覆盖 x.jar / x.jar.disabled / x.jar.disable 三种状态。
            String base = stripVariantSuffix(path);
            String key = Fs.pathKey(base);
            if (key.isEmpty()) {
                info(Text.of(Msg.SYNC_RULES_BAD_PATH, path));
                continue;
            }
            // 清单里仍在的路径绝不删，连同它的禁用变体一起保护
            if (live.contains(key) || live.contains(key + DISABLED_SUFFIX)
                    || live.contains(key + LEGACY_DISABLE_SUFFIX)) {
                liveHit++;
                continue;
            }
            if (!selfKey.isEmpty() && key.equals(selfKey)) {
                continue;
            }
            if (rules.isIgnored(key)) {
                ignoreHit++;
                continue;
            }
            if (insideArchive(key, archiveDirs)) {
                archiveHit++;
                continue;
            }
            Path target = safeRemovalTarget(base);
            // 只有禁用副本时同样排进计划：该路径已不在清单里，留着不会再用到。
            // 删除列表可以长期挂在发布端，路径不存在时静默跳过。
            if (target == null || existingVariant(target) == null || !queued.add(key)) {
                continue;
            }
            retiredPaths.add(base);
            files.add(base);
            retired++;
        }
        info(Text.of(Msg.RETIRED_PLAN_COUNT, retired));
        if (liveHit > 0) {
            info(Text.of(Msg.RETIRED_SKIPPED_LIVE, liveHit));
        }
        if (ignoreHit > 0) {
            info(Text.of(Msg.RETIRED_SKIPPED_IGNORE, ignoreHit));
        }
        if (archiveHit > 0) {
            info(Text.of(Msg.RETIRED_SKIPPED_ARCHIVE, archiveHit));
        }

        if (old == null) {
            return;
        }
        Map<String, Manifest.FileEntry> byIdentity = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            byIdentity.put(item.identity(), item);
        }
        int protectedCount = 0;
        int archiveProtected = 0;
        for (Manifest.FileEntry item : old.files) {
            Manifest.FileEntry replacement = byIdentity.get(item.identity());
            if (replacement != null && replacement.path.equals(item.path)) {
                continue;
            }
            if (rules.isIgnored(item.path)) {
                protectedCount++;
                continue;
            }
            String key = Fs.pathKey(item.path);
            if (key.isEmpty() || key.equals(selfKey)) {
                continue;
            }
            if (insideArchive(key, archiveDirs)) {
                archiveProtected++;
                continue;
            }
            if (queued.add(key)) {
                files.add(item.path);
            }
        }
        if (protectedCount > 0) {
            info(Text.of(Msg.IGNORE_SKIPPED_REMOVAL, protectedCount));
        }
        if (archiveProtected > 0) {
            info(Text.of(Msg.ARCHIVE_SKIPPED_REMOVAL, archiveProtected));
        }
        // 从清单里移除压缩包条目**不再删除**已解压目录：条目消失无法表达「要删」的意图，
        // 而且会把玩家改过的 config 一起删掉。需要清理时保留/新增 Over=true 的包，
        // 覆盖解压时会先删旧目录；不再提供「删条目 = 删目录」。
        dirs.sort(Comparator.comparingInt((String dir) -> -depth(dir)));
    }

    /** 当前清单的归档解压目录（规范化键），用于保护归档内的文件不被逐文件删除或改名 */
    private static List<String> archiveDirsOf(Manifest manifest) {
        List<String> out = new ArrayList<>();
        for (Manifest.ArchiveEntry item : manifest.archives) {
            String dir = Fs.pathKey(item.dir);
            if (!dir.isEmpty()) {
                out.add(dir);
            }
        }
        return out;
    }

    /** 路径是否落在任一归档解压目录内（含目录本身） */
    private static boolean insideArchive(String key, List<String> archiveDirs) {
        for (String dir : archiveDirs) {
            if (key.equals(dir) || Fs.under(key, dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 空压缩包删除目录是否执行。
     *
     * 旁文件不可用时一律不删；存在屏蔽规则时整目录删除无法保证「屏蔽项不动」，
     * 按约定跳过——需要屏蔽的实例通常也不使用「空包删除目录」这一形态。
     */
    private boolean canRemoveDirectory(String dir) {
        if (!rulesAvailable) {
            info(Text.of(Msg.EMPTY_ARCHIVE_SKIPPED_UNAVAILABLE, dir));
            return false;
        }
        if (rules.ignoreCount() > 0) {
            info(Text.of(Msg.EMPTY_ARCHIVE_SKIPPED_IGNORE, dir));
            return false;
        }
        return true;
    }

    /**
     * `Over=true` 归档解压前清理旧目录：屏蔽规则命中的文件保留，其余删除。
     * 旁文件不可用时不清空，只覆盖同名文件，避免误删无法确认的路径。
     */
    private void clearArchiveDirectory(String dir, Path target) throws IOException {
        // 归档是「整包替换目录」的语义，不属于逐文件删除：这里始终按旧行为清空。
        // 旁文件可用时保留屏蔽项；不可用时按旧行为整目录清空，兼容其他整合包的 zip 用法。
        if (!rulesAvailable || rules.ignoreCount() == 0) {
            Fs.deleteDirectory(target);
            return;
        }
        int kept = Fs.deleteDirectory(target, options.gameDir, key -> rules.isIgnored(key));
        if (kept > 0) {
            info(Text.of(Msg.ARCHIVE_KEPT_PROTECTED, dir, kept));
        }
    }

    /** 删除列表只接受精确文件相对路径；目录、通配与越界写法一律拒绝 */
    private Path safeRemovalTarget(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty() || value.endsWith("/") || value.endsWith("\\")
                || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            info(Text.of(Msg.SYNC_RULES_BAD_PATH, path));
            return null;
        }
        try {
            return Fs.resolveSafe(options.gameDir, value);
        } catch (IOException | RuntimeException e) {
            info(Text.of(Msg.SYNC_RULES_BAD_PATH, path));
            return null;
        }
    }

    private void applyRemovals(Set<String> files, List<String> dirs) throws IOException {
        if (!rulesAvailable) {
            return;
        }
        for (String path : files) {
            // 兜底：任何删除入口都不得越过屏蔽规则
            if (rules.isIgnored(path)) {
                continue;
            }
            Path target = Fs.resolveSafe(options.gameDir, path);
            if (Files.isDirectory(target)) {
                info(Text.of(Msg.SYNC_RULES_BAD_PATH, path));
                continue;
            }
            if (deleteVariants(target) == 0) {
                continue;
            }
            Msg reason = retiredPaths.contains(path)
                    ? Msg.REMOVING_RETIRED_FILE : Msg.REMOVING_OLD_FILE_PATH;
            info(Text.of(reason, path));
        }
        for (String dir : dirs) {
            Path target = Fs.resolveSafe(options.gameDir, dir);
            if (Files.exists(target)) {
                Fs.deleteDirectory(target);
                info(Text.of(Msg.REMOVING_OLD_DIR, dir));
            }
        }
    }

    /**
     * 删除一个路径及其禁用变体
     *
     * 只用于「已不在清单里」的路径：这些文件不会再用到，留着既占磁盘，也会让
     * 「同一模组两份副本」的排查变复杂。仍在清单里的路径由计划阶段拦下。
     *
     * - `target`: 清单里记录的原始路径
     *
     * # 返回值
     *
     * 实际删掉的文件数
     */
    private int deleteVariants(Path target) throws IOException {
        int count = 0;
        for (String suffix : new String[]{"", DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            Path candidate = suffix.isEmpty() ? target : withSuffix(target, suffix);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            Files.deleteIfExists(candidate);
            count++;
            removedCount++;
            if (!suffix.isEmpty()) {
                info(Text.of(Msg.RETIRED_DISABLED_REMOVED, candidate.getFileName().toString()));
            }
        }
        return count;
    }

    /**
     * 去掉路径末尾的禁用后缀，保留其余原始写法
     *
     * 与 {@link SyncRules#stripDisabledSuffix} 的区别是不折叠大小写：
     * 结果要拿去拼真实文件路径，只有比较时才用规范化键。
     */
    private static String stripVariantSuffix(String path) {
        String value = path == null ? "" : path.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            if (lower.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    /**
     * 算出要下载的文件与各自落盘位置。
     *
     * 禁用中的文件也算装好：按内容比对，别把玩家停用的模组又装回启用；
     * 自选模组的内容始终同步，勾选与否只决定装成启用名还是 .disabled。
     *
     * - `current`: 远程清单
     * - `selfJar`: 运行中的原始更新器 jar 对游戏目录的相对路径；空串表示不跳过
     */
    private List<PlannedFile> changedFiles(Manifest current, String selfJar)
            throws IOException {
        List<PlannedFile> out = new ArrayList<>();
        int ignored = 0;
        for (Manifest.FileEntry item : current.files) {
            if (rules.isIgnored(item.path)) {
                ignored++;
                continue;
            }
            // 更新器自身在运行中无法被覆盖（Windows 文件锁）：升级走自更新通道
            if (item.path.equals(selfJar)) {
                info(Text.of(Msg.SELF_JAR_SKIPPED, item.path));
                continue;
            }
            Path target = Fs.resolveSafe(options.gameDir, item.path);
            Path existing = existingVariant(target);
            if (existing != null && Downloader.matches(existing, item.sha1, item.sha256)) {
                skippedCount++;
                continue;
            }
            if (item.optional) {
                boolean selected = Boolean.TRUE.equals(selections.get(item.path));
                out.add(new PlannedFile(item, selected ? target : withSuffix(target, DISABLED_SUFFIX)));
                continue;
            }
            out.add(new PlannedFile(item, target));
        }
        if (ignored > 0) {
            info(Text.of(Msg.IGNORE_SKIPPED_DOWNLOAD, ignored));
        }
        return out;
    }

    /** 文件名是不是禁用状态（.disabled 或旧版 .disable） */
    private static boolean isDisabled(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(DISABLED_SUFFIX) || name.endsWith(LEGACY_DISABLE_SUFFIX);
    }

    /**
     * 本地已有的文件：原始名，或追加了 .disabled / .disable 后缀的禁用版。
     *
     * 模组、资源包被禁用后文件名会变，按原始路径判断存在性会把「已经装好但停用」当成没装，
     * 每次同步覆盖一次，玩家停用的东西就又被装回启用了。
     */
    private static Path existingVariant(Path target) {
        if (Files.isRegularFile(target)) {
            return target;
        }
        for (String suffix : new String[]{DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            Path candidate = withSuffix(target, suffix);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 给文件名追加后缀，如 `mods/a.jar` → `mods/a.jar.disabled` */
    private static Path withSuffix(Path target, String suffix) {
        return target.resolveSibling(target.getFileName().toString() + suffix);
    }

    /** 条目的内容哈希：SHA1 优先，没有时用 SHA256 */
    private static String contentHash(Manifest.FileEntry item) {
        return item.sha1.isEmpty() ? item.sha256 : item.sha1;
    }

    /**
     * 运行中的原始更新器 jar 对游戏目录的相对路径
     *
     * jar 不在游戏目录内、跨盘或找不到时返回空串——相对化会带 .. 或抛异常，
     * 而清单路径不允许 ..，永远不会与这种值相等。
     */
    private String selfJarRelative() {
        Path jar = Updater.originalJarPath();
        if (jar == null) {
            return "";
        }
        try {
            return options.gameDir.toAbsolutePath().normalize().relativize(
                    jar.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 有界面、允许交互时才弹窗；不要求具体窗口类型（从 update/ 包运行时类加载器分裂，instanceof 不可用） */
    private boolean canAsk() {
        if (!options.ask || !options.gui || ProgressWindow.guiUnavailable()) {
            return false;
        }
        try {
            return !java.awt.GraphicsEnvironment.isHeadless();
        } catch (Throwable e) {
            // 图形环境不可用（安卓启动器里 cacio 尚未接管等）：按无界面继续
            return false;
        }
    }

    /** 进度窗上的「自选模组」按钮：跨类加载器不能按类型接线，按文字在窗口树里定位 */
    private javax.swing.JButton findOptionalButton() {
        javax.swing.JFrame frame = ownerFrame();
        if (frame == null) {
            return null;
        }
        return findButtonByText(frame.getContentPane(), Text.of(Msg.OPTIONAL_MODS_BUTTON));
    }

    private static javax.swing.JButton findButtonByText(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton
                    && text.equals(((javax.swing.JButton) child).getText())) {
                return (javax.swing.JButton) child;
            }
            if (child instanceof java.awt.Container) {
                javax.swing.JButton found = findButtonByText((java.awt.Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 找正在显示的进度窗当弹窗父窗口；没有时返回 null，对话框居屏幕中 */
    private static javax.swing.JFrame ownerFrame() {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof javax.swing.JFrame && window.isShowing()) {
                return (javax.swing.JFrame) window;
            }
        }
        return null;
    }

    private Path existingVariantQuiet(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return existingVariant(Fs.resolveSafe(options.gameDir, path));
        } catch (IOException e) {
            return null;
        }
    }

    /** --dry-run 时把自选模组的启用 / 禁用计划打出来 */
    private void printOptionalPlan(Manifest current) {
        int enabled = 0;
        int disabled = 0;
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            if (Boolean.TRUE.equals(selections.get(item.path))) {
                enabled++;
            } else {
                disabled++;
            }
        }
        info(Text.of(Msg.OPTIONAL_APPLY_COUNT, enabled, disabled));
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            info("  - " + (Boolean.TRUE.equals(selections.get(item.path))
                    ? Text.of(Msg.MARK_ENABLE) : Text.of(Msg.MARK_DISABLE)) + item.path);
        }
    }

    private List<ArchivePlan> archivePlans(Manifest old, Manifest current, List<String> removedDirs) {
        Map<String, Manifest.ArchiveEntry> oldArchives = new HashMap<>();
        if (old != null) {
            for (Manifest.ArchiveEntry item : old.archives) {
                oldArchives.put(item.file, item);
            }
        }
        Set<String> skipped = new java.util.HashSet<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            if (archiveUnchanged(oldArchives.get(item.file), item)) {
                skipped.add(item.dir);
            }
        }
        Set<String> cleared = new java.util.HashSet<>(removedDirs);
        for (Manifest.ArchiveEntry item : current.archives) {
            if (!skipped.contains(item.dir) && item.deleteOld) {
                cleared.add(item.dir);
            }
        }
        List<ArchivePlan> out = new ArrayList<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            boolean skip = skipped.contains(item.dir);
            if (skip) {
                for (String ancestor : cleared) {
                    if (Fs.under(item.dir, ancestor)) {
                        skip = false;
                        break;
                    }
                }
            }
            out.add(new ArchivePlan(item, null, skip));
        }
        return out;
    }

    private boolean archiveUnchanged(Manifest.ArchiveEntry old, Manifest.ArchiveEntry current) {
        if (old == null || !old.dir.equals(current.dir) || old.deleteOld != current.deleteOld) {
            return false;
        }
        String a = old.sha1.isEmpty() ? old.sha256 : old.sha1;
        String b = current.sha1.isEmpty() ? current.sha256 : current.sha1;
        if (a.isEmpty() || !a.equalsIgnoreCase(b)) {
            return false;
        }
        try {
            Path target = Fs.resolveSafe(options.gameDir, current.dir);
            return Files.isDirectory(target);
        } catch (IOException e) {
            return false;
        }
    }

    private void printPlan(Manifest current, Manifest old, Set<String> removedFiles,
                           List<String> removedDirs) {
        info(Text.of(Msg.PLAN_MANIFEST,
                current.name.isEmpty() ? Text.of(Msg.UNNAMED) : current.name, current.packVersion));
        info(Text.of(Msg.PLAN_RETIRED_FILES, retiredPaths.size()));
        for (String path : retiredPaths) {
            info("  - " + path);
        }
        int otherRemovals = 0;
        for (String path : removedFiles) {
            if (!retiredPaths.contains(path)) {
                otherRemovals++;
            }
        }
        info(Text.of(Msg.PLAN_REMOVE_FILES, otherRemovals));
        for (String path : removedFiles) {
            if (!retiredPaths.contains(path)) {
                info("  - " + path);
            }
        }
        info(Text.of(Msg.PLAN_REMOVE_DIRS, removedDirs.size()));
        for (String dir : removedDirs) {
            info("  - " + dir);
        }
        info(Text.of(Msg.PLAN_CHECK_FILES, current.files.size(), current.archives.size()));
        if (current.base != null && (old == null || old.base == null)) {
            info(Text.of(Msg.PLAN_BASE, current.base.file));
        }
    }

    private void saveState(byte[] data) throws IOException {
        Files.createDirectories(options.stateDir);
        Path target = options.stateDir.resolve("last-manifest.json");
        Path temp = options.stateDir.resolve("last-manifest.json.tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(temp);
    }

    private void progress(int done, int total, String detail) {
        if (!options.quiet && total > 0) {
            Log.info("[progress] " + done + "/" + total);
        }
        // 阶段带整体计数，细节行留给出当前文件名
        ui.stage(Text.of(Msg.DOWNLOADING_FILES_PROGRESS, done, total));
        ui.step(done, detail);
    }

    /** 字节数的 MB 显示，与日志里的进度格式一致 */
    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private void info(String message) {
        if (!options.quiet) {
            Log.info(message);
        }
    }

    /**
     * 按选项里的线程数并发下载
     *
     * 任一条失败就取消其余任务并抛出第一个错误，避免半套文件继续被解压。
     *
     * - `tasks`: 待下载条目
     * - `total`: 条目总数，进度按它报
     *
     * # 返回值
     *
     * 实际完成的条目数
     */
    private int downloadAll(List<DownloadTask> tasks, int total) throws IOException {
        if (tasks.isEmpty()) {
            return 0;
        }
        int threads = Math.max(1, Math.min(options.threads, tasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "mml-server-sync-download");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger finished = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        try {
            for (DownloadTask task : tasks) {
                futures.add(pool.submit(() -> {
                    checkCancelled();
                    String url = task.entry == null
                            ? task.url
                            : downloader.resolveFile(task.entry);
                    downloader.download(url, task.target, task.sha1, task.sha256);
                    progress(finished.incrementAndGet(), total,
                            task.target.getFileName().toString());
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancelledException || cause instanceof SyncAbortException) {
                        throw (RuntimeException) cause;
                    }
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException(cause == null ? Text.of(Msg.DOWNLOAD_FAILED) : cause.toString(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(Text.of(Msg.DOWNLOAD_INTERRUPTED), e);
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 被取消的下载来不及走自己的清理（线程被打断、进程可能马上退出），这里兜底删一遍
            for (DownloadTask task : tasks) {
                try {
                    Files.deleteIfExists(task.target.resolveSibling(
                            task.target.getFileName().toString() + ".part"));
                } catch (IOException ignored) {
                    // 删不掉只是留个临时文件，下次同步会覆盖
                }
            }
        }
        return finished.get();
    }

    /** 需要下载的一个文件：清单条目与落盘位置 */
    private static final class PlannedFile {
        final Manifest.FileEntry item;
        final Path target;

        PlannedFile(Manifest.FileEntry item, Path target) {
            this.item = item;
            this.target = target;
        }
    }

    /** 一个待下载条目 */
    private static final class DownloadTask {
        /// 直接可用的地址；为空时用 `entry` 解析平台地址
        final String url;
        /// 落盘位置
        final Path target;
        /// SHA1，空串表示不校验
        final String sha1;
        /// SHA256，空串表示不校验
        final String sha256;
        /// 清单里的文件条目；基础包与配置包为 null
        final Manifest.FileEntry entry;

        DownloadTask(String url, Path target, String sha1, String sha256, Manifest.FileEntry entry) {
            this.url = url;
            this.target = target;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.entry = entry;
        }
    }

    private static int depth(String path) {
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static String safeName(String name) {
        String out = name.replace('\\', '/');
        int slash = out.lastIndexOf('/');
        if (slash >= 0) {
            out = out.substring(slash + 1);
        }
        return out.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String toUrl(String value) throws IOException {
        String text = value.trim();
        // Windows 盘符路径（D:\x、D:/x）不是 URL：交给下面的 scheme 正则会被当成「D:」协议
        if (isWindowsPath(text)) {
            return fileUri(text);
        }
        if (text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return text;
        }
        return fileUri(text);
    }

    /** 是不是带盘符的 Windows 绝对路径，如 D:\dir\server.json 或 D:/dir/server.json */
    private static boolean isWindowsPath(String text) {
        if (text.length() < 3 || text.charAt(1) != ':' || !isAsciiLetter(text.charAt(0))) {
            return false;
        }
        char separator = text.charAt(2);
        return separator == '\\' || separator == '/';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    /** 本地路径转 file URI，相对路径按当前目录解析 */
    private static String fileUri(String path) throws IOException {
        return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toUri().toString();
    }

    private static final class ArchivePlan {
        final Manifest.ArchiveEntry item;
        final Path temp;
        final boolean skip;

        ArchivePlan(Manifest.ArchiveEntry item, Path temp, boolean skip) {
            this.item = item;
            this.temp = temp;
            this.skip = skip;
        }
    }
}
