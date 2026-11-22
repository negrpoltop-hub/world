package dev.restore.worldrestore;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class WorldRestorePlugin extends JavaPlugin implements Listener {

    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastReset = new HashMap<>();

    private final Map<String, Map<Long, String>> placed = new HashMap<>();
    private boolean unsaved;
    private Path dataFile;

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private Path backupRoot;

    private boolean blocksMode;
    private boolean requireEmpty;
    private int blocksPerTick;
    private long intervalMs;
    private boolean onlyIfVisited;
    private Set<String> excluded;
    private Set<String> managed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        dataFile = getDataFolder().toPath().resolve("placed-blocks.tsv");
        loadPlaced();

        long period = Math.max(5, getConfig().getLong("check-interval-seconds", 30)) * 20L;
        getServer().getScheduler().runTaskTimer(this, this::tick, period, period);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (unsaved) { unsaved = false; savePlaced(false); }
        }, 6000L, 6000L);

        if (!blocksMode && getConfig().getBoolean("auto-backup-missing", true)) {
            getServer().getScheduler().runTask(this, () -> {
                for (World w : Bukkit.getWorlds()) {
                    if (isManaged(w) && !Files.exists(backupRoot.resolve(w.getName()))) {
                        getLogger().info("Нет бэкапа для мира '" + w.getName() + "', создаю...");
                        backup(w.getName(), null);
                    }
                }
            });
        }
    }

    @Override
    public void onDisable() {
        savePlaced(true);
    }

    private void loadSettings() {
        reloadConfig();
        backupRoot = getServer().getWorldContainer().toPath()
                .resolve(getConfig().getString("backup-dir", "world-backups"));
        excluded = lower(getConfig().getStringList("excluded-worlds"));
        managed = lower(getConfig().getStringList("managed-worlds"));
        intervalMs = Math.max(1, getConfig().getLong("reset-interval-minutes", 20)) * 60_000L;
        onlyIfVisited = getConfig().getBoolean("only-if-visited", true);
        blocksMode = !"world".equalsIgnoreCase(getConfig().getString("reset-mode", "blocks"));
        requireEmpty = getConfig().getBoolean("require-empty", true);
        blocksPerTick = Math.max(10, getConfig().getInt("blocks-per-tick", 200));
    }

    private static Set<String> lower(List<String> list) {
        return list.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static String lk(World w) {
        return w.getName().toLowerCase(Locale.ROOT);
    }

private boolean isManaged(World w) {
        String key = lk(w);
        if (excluded.contains(key)) return false;
        if (!managed.isEmpty()) return managed.contains(key);
        return !w.equals(Bukkit.getWorlds().get(0));
    }

    // ===== Отслеживание блоков, которые поставили игроки =====

    private void track(World w, long blockKey, String originalData) {
        if (!blocksMode || !isManaged(w)) return;
        placed.computeIfAbsent(lk(w), k -> new HashMap<>()).putIfAbsent(blockKey, originalData);
        unsaved = true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!blocksMode) return;
        World w = e.getBlock().getWorld();
        if (e instanceof BlockMultiPlaceEvent m) {
            for (BlockState st : m.getReplacedBlockStates()) {
                track(w, st.getBlock().getBlockKey(), st.getBlockData().getAsString());
            }
        } else {
            BlockState st = e.getBlockReplacedState();
            track(w, e.getBlock().getBlockKey(), st.getBlockData().getAsString());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!blocksMode) return;
        Block b = e.getBlock();
        track(b.getWorld(), b.getBlockKey(), b.getBlockData().getAsString());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFlow(BlockFromToEvent e) {
        if (!blocksMode || placed.isEmpty()) return;
        Block from = e.getBlock();
        Map<Long, String> m = placed.get(lk(from.getWorld()));
        if (m == null || !m.containsKey(from.getBlockKey())) return;
        Block to = e.getToBlock();
        track(to.getWorld(), to.getBlockKey(), to.getBlockData().getAsString());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        dirty.add(lk(e.getPlayer().getWorld()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        dirty.add(lk(e.getPlayer().getWorld()));
    }

    // ===== Периодическая проверка =====

    private void tick() {
        long now = System.currentTimeMillis();
        for (World w : new ArrayList<>(Bukkit.getWorlds())) {
            if (!isManaged(w)) continue;
            String key = lk(w);
            if (busy.contains(key)) continue;

            long last = lastReset.computeIfAbsent(key, k -> now);
            if (now - last < intervalMs) continue;
            boolean hasPlayers = !w.getPlayers().isEmpty();

            if (blocksMode) {
                if (hasPlayers && requireEmpty) continue;
                Map<Long, String> m = placed.get(key);
                if (m == null || m.isEmpty()) { lastReset.put(key, now); continue; }
                resetBlocks(w, null);
            } else {
                if (hasPlayers) continue;
                if (onlyIfVisited && !dirty.contains(key)) { lastReset.put(key, now); continue; }
                restore(w.getName(), null);
            }
        }
    }

    // ===== Режим BLOCKS: удаление поставленных блоков =====

    public void resetBlocks(World w, CommandSender fb) {
        String key = lk(w);
        Map<Long, String> map = placed.get(key);
        if (map == null || map.isEmpty()) { msg(fb, "В мире " + w.getName() + " нет поставленных блоков."); return; }
        if (!busy.add(key)) { msg(fb, "С миром уже идёт операция."); return; }

        List<Map.Entry<Long, String>> queue = new ArrayList<>(Map.copyOf(map).entrySet());
        int total = queue.size();
        String name = w.getName();

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (Bukkit.getWorld(name) != w) {

busy.remove(key);
                    cancel();
                    return;
                }
                int end = Math.min(i + blocksPerTick, queue.size());
                for (; i < end; i++) {
                    Map.Entry<Long, String> en = queue.get(i);
                    try {
                        Block b = w.getBlockAtKey(en.getKey());
                        b.setBlockData(Bukkit.createBlockData(en.getValue()), false);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("Неверные данные блока: " + en.getValue());
                    }
                    map.remove(en.getKey());
                }
                if (i >= queue.size()) {
                    lastReset.put(key, System.currentTimeMillis());
                    busy.remove(key);
                    unsaved = true;
                    savePlaced(false);
                    String r = "Мир '" + name + "': удалено поставленных блоков — " + total + ".";
                    getLogger().info(r);
                    msg(fb, r);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void loadPlaced() {
        if (!Files.exists(dataFile)) return;
        try {
            for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                placed.computeIfAbsent(p[0], k -> new HashMap<>()).put(Long.parseLong(p[1]), p[2]);
            }
            getLogger().info("Загружено отслеживаемых блоков: "
                    + placed.values().stream().mapToInt(Map::size).sum());
        } catch (IOException | NumberFormatException ex) {
            getLogger().severe("Не удалось прочитать " + dataFile + ": " + ex.getMessage());
        }
    }

    private void savePlaced(boolean sync) {
        List<String> lines = new ArrayList<>();
        placed.forEach((world, map) ->
                map.forEach((k, v) -> lines.add(world + "\t" + k + "\t" + v)));
        Runnable write = () -> {
            try {
                Files.createDirectories(dataFile.getParent());
                Files.write(dataFile, lines, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                getLogger().severe("Не удалось сохранить " + dataFile + ": " + ex.getMessage());
            }
        };
        if (sync) write.run();
        else getServer().getScheduler().runTaskAsynchronously(this, write);
    }

    // ===== Режим WORLD: восстановление мира из бэкапа =====

    public void restore(String worldName, CommandSender fb) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { msg(fb, "Мир не загружен: " + worldName); return; }
        String key = lk(w);
        if (excluded.contains(key)) { msg(fb, "Мир исключён из сброса (аванпост)."); return; }
        if (w.equals(Bukkit.getWorlds().get(0))) { msg(fb, "Основной мир восстанавливать нельзя."); return; }

        Path backup = backupRoot.resolve(w.getName());
        if (!Files.isDirectory(backup)) { msg(fb, "Нет бэкапа для мира " + w.getName()); return; }
        if (!w.getPlayers().isEmpty()) { msg(fb, "В мире есть игроки."); return; }
        if (!busy.add(key)) { msg(fb, "С миром уже идёт операция."); return; }

        World.Environment env = w.getEnvironment();
        Path folder = w.getWorldFolder().toPath();
        String name = w.getName();

        if (!Bukkit.unloadWorld(w, false)) {
            busy.remove(key);
            msg(fb, "Не удалось выгрузить мир " + name);
            return;
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = true;
            try {
                deleteRecursive(folder);

copyRecursive(backup, folder);
            } catch (IOException ex) {
                ok = false;
                getLogger().severe("Ошибка восстановления '" + name + "': " + ex.getMessage());
            }
            final boolean success = ok;
            getServer().getScheduler().runTask(this, () -> {
                new WorldCreator(name).environment(env).createWorld();
                dirty.remove(key);
                lastReset.put(key, System.currentTimeMillis());
                busy.remove(key);
                String r = success ? "Мир '" + name + "' восстановлен из бэкапа."
                                   : "Мир '" + name + "' восстановлен с ошибками, см. консоль.";
                getLogger().info(r);
                msg(fb, r);
            });
        });
    }

    public void backup(String worldName, CommandSender fb) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { msg(fb, "Мир не загружен: " + worldName); return; }
        String key = lk(w);
        if (!busy.add(key)) { msg(fb, "С миром уже идёт операция."); return; }

        w.save();
        boolean autoSave = w.isAutoSave();
        w.setAutoSave(false);
        Path folder = w.getWorldFolder().toPath();
        Path target = backupRoot.resolve(w.getName());
        String name = w.getName();

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = true;
            try {
                deleteRecursive(target);
                copyRecursive(folder, target);
            } catch (IOException ex) {
                ok = false;
                getLogger().severe("Ошибка бэкапа '" + name + "': " + ex.getMessage());
            }
            final boolean success = ok;
            getServer().getScheduler().runTask(this, () -> {
                w.setAutoSave(autoSave);
                busy.remove(key);
                msg(fb, success ? "Бэкап мира '" + name + "' создан." : "Ошибка бэкапа, см. консоль.");
            });
        });
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ===== Команды =====

    private void msg(CommandSender s, String text) {
        if (s != null) s.sendMessage("[WorldRestore] " + text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {

msg(sender, "/wr <clear|status|reload|backup|restore> [мир]");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> { loadSettings(); msg(sender, "Конфиг перезагружен."); }
            case "status" -> {
                long now = System.currentTimeMillis();
                msg(sender, "Режим: " + (blocksMode ? "blocks" : "world"));
                for (World w : Bukkit.getWorlds()) {
                    String k = lk(w);
                    if (excluded.contains(k)) { msg(sender, w.getName() + " — исключён (аванпост)"); continue; }
                    if (!isManaged(w)) { msg(sender, w.getName() + " — не управляется"); continue; }
                    long left = Math.max(0, (intervalMs - (now - lastReset.getOrDefault(k, now))) / 1000);
                    String extra = blocksMode
                            ? ", поставлено блоков: " + placed.getOrDefault(k, Map.of()).size()
                            : (dirty.contains(k) ? ", изменён" : ", чистый");
                    msg(sender, w.getName() + " — игроков: " + w.getPlayers().size() + extra
                            + ", сброс через " + left + " с");
                }
            }
            case "clear" -> {
                if (!needWorld(sender, args)) break;
                World w = Bukkit.getWorld(args[1]);
                if (w == null) msg(sender, "Мир не загружен: " + args[1]);
                else if (!isManaged(w)) msg(sender, "Этот мир не управляется (аванпост/основной).");
                else resetBlocks(w, sender);
            }
            case "backup" -> { if (needWorld(sender, args)) backup(args[1], sender); }
            case "restore" -> { if (needWorld(sender, args)) restore(args[1], sender); }
            default -> msg(sender, "Неизвестная подкоманда.");
        }
        return true;
    }

    private boolean needWorld(CommandSender s, String[] args) {
        if (args.length < 2) { msg(s, "Укажите мир."); return false; }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("clear", "status", "reload", "backup", "restore");
        if (args.length == 2 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("reload")) {
            return Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
        }
        return List.of();
    }
}
