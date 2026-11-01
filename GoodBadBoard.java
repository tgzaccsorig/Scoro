package ru.goodbadworld.board;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class GoodBadBoard extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Set<UUID> disabled = new HashSet<>();

    private Object economy;
    private Method economyGetBalance;
    private Method papiSet;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookIntegrations();
        getServer().getPluginManager().registerEvents(this, this);
        startTask();
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void startTask() {
        Bukkit.getScheduler().cancelTasks(this);
        long interval = Math.max(1, getConfig().getLong("update-interval", 20));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                update(p);
            }
        }, 20L, interval);
    }

    private void hookIntegrations() {
        economy = null;
        economyGetBalance = null;
        papiSet = null;

        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
                RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ecoClass);
                if (rsp != null) {
                    economy = rsp.getProvider();
                    economyGetBalance = ecoClass.getMethod("getBalance", OfflinePlayer.class);
                    getLogger().info("Vault найден, деньги берутся из экономики.");
                }
            } catch (Throwable t) {
                getLogger().warning("Не удалось подключить Vault: " + t.getMessage());
            }
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                papiSet = papi.getMethod("setPlaceholders", Player.class, String.class);
                getLogger().info("PlaceholderAPI найден.");
            } catch (Throwable t) {
                getLogger().warning("Не удалось подключить PlaceholderAPI: " + t.getMessage());
            }
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // небольшая задержка, чтобы Vault/кланы успели загрузить данные игрока
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) update(e.getPlayer());
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ---------- scoreboard ----------

    private void update(Player player) {
        if (disabled.contains(player.getUniqueId())) {
            return;
        }

        List<String> lines = getConfig().getStringList("lines");
        if (lines.size() > 15) {
            lines = lines.subList(0, 15);
        }

        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective("gbboard");

        // создаём личный scoreboard, если у игрока основной/чужой
        if (obj == null || board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = board.registerNewObjective("gbboard", Criteria.DUMMY, Component.text("gbboard"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(board);
        }

        obj.displayName(LEGACY.deserialize(getConfig().getString("title", "&e&lgoodbadworld")));

        int size = lines.size();
        for (int i = 0; i < size; i++) {
            String entry = entryFor(i);
            Team team = board.getTeam("gbl" + i);
            if (team == null) {
                team = board.registerNewTeam("gbl" + i);
                team.addEntry(entry);
            }
            team.prefix(LEGACY.deserialize(replace(player, lines.get(i))));
            obj.getScore(entry).setScore(size - i);
        }

        // убрать лишние строки, если в конфиге их стало меньше
        for (int i = size; i < 15; i++) {
            Team team = board.getTeam("gbl" + i);
            if (team != null) {
                board.resetScores(entryFor(i));
                team.unregister();
            }
        }
    }

    /** Уникальная невидимая запись для каждой строки. */
    private String entryFor(int index) {
        return "\u00a7" + Integer.toHexString(index) + "\u00a7r";
    }

    private String replace(Player p, String line) {
        return line
                .replace("{nick}", p.getName())
                .replace("{ping}", formatPing(p.getPing()))
                .replace("{money}", getMoney(p))
                .replace("{clan}", getClan(p));
    }

    private String formatPing(int ping) {
        String color = ping <= 80 ? "&a" : ping <= 150 ? "&e" : "&c";
        return color + ping + " мс";
    }

    private String getMoney(Player p) {
        if (economy != null && economyGetBalance != null) {
            try {
                double bal = (double) economyGetBalance.invoke(economy, p);
                return getConfig().getString("currency-symbol", "$") + formatNumber(bal);
            } catch (Throwable ignored) {
            }
        }
        String ph = getConfig().getString("money-placeholder", "");
        if (!ph.isEmpty() && papiSet != null) {
            String res = applyPapi(p, ph);
            if (!res.equals(ph)) return res;
        }
        return "0";
    }

    private String getClan(Player p) {
        String none = getConfig().getString("no-clan", "Нет");
        String ph = getConfig().getString("clan-placeholder", "");
        if (ph.isEmpty() || papiSet == null) return none;
        String res = applyPapi(p, ph).trim();
        if (res.isEmpty() || res.equals(ph)) return none;
        return res;
    }

    private String applyPapi(Player p, String text) {
        try {
            return (String) papiSet.invoke(null, p, text);
        } catch (Throwable t) {
            return text;
        }
    }

    private String formatNumber(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.US, "%,d", (long) v).replace(',', ' ');
        }
        return String.format(Locale.US, "%,.2f", v).replace(',', ' ');
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "toggle";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("goodbadboard.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            hookIntegrations();
            startTask();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(getConfig().getString("messages.players-only", "Только для игроков."));
            return true;
        }

        if (disabled.remove(player.getUniqueId())) {
            update(player);
            player.sendMessage(msg("toggled-on"));
        } else {
            disabled.add(player.getUniqueId());
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            player.sendMessage(msg("toggled-off"));
        }
        return true;
    }

    private Component msg(String key) {
        return LEGACY.deserialize(getConfig().getString("messages." + key, key));
    }
}
