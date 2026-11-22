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
