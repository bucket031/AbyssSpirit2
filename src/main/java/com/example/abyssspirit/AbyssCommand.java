package com.example.abyssspirit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /abyss set <player>           turn a player into the Abyss (admin)
 * /abyss clear <player>         turn them back into a normal player (admin)
 * /abyss form <small|big> [player]   switch bodies (the Abyss can switch itself)
 * /abyss list                   who is the Abyss right now (admin)
 * /abyss reload                 reload config.yml (admin)
 */
public final class AbyssCommand implements TabExecutor {

    private static final String ADMIN = "abyss.admin";

    private final AbyssSpirit plugin;

    public AbyssCommand(AbyssSpirit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("form")) {
            form(sender, args);
            return true;
        }

        if (!sender.hasPermission(ADMIN)) {
            msg(sender, "Only admins can do that.", NamedTextColor.RED);
            return true;
        }

        switch (sub) {
            case "set" -> set(sender, args);
            case "clear" -> clear(sender, args);
            case "list" -> list(sender);
            case "reload" -> {
                plugin.reloadConfig();
                msg(sender, "AbyssSpirit config reloaded.", NamedTextColor.GREEN);
            }
            default -> help(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------ set / clear

    private void set(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /abyss set <player>", NamedTextColor.YELLOW);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "Player '" + args[1] + "' is not online.", NamedTextColor.RED);
            return;
        }
        plugin.abyss().set(target);
        msg(sender, target.getName() + " is now the Abyss.", NamedTextColor.GREEN);
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /abyss clear <player>", NamedTextColor.YELLOW);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "Player '" + args[1] + "' is not online.", NamedTextColor.RED);
            return;
        }
        boolean was = plugin.abyss().clear(target);
        msg(sender, was ? target.getName() + " is a normal player again."
                : target.getName() + " was not the Abyss.", NamedTextColor.GREEN);
    }

    private void list(CommandSender sender) {
        List<String> names = plugin.abyss().names();
        msg(sender, names.isEmpty() ? "Nobody is the Abyss right now."
                : "The Abyss: " + String.join(", ", names), NamedTextColor.GOLD);
    }

    // ------------------------------------------------------------------- form

    private void form(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /abyss form <small|big> [player]", NamedTextColor.YELLOW);
            return;
        }

        Form form = Form.fromInput(args[1]);
        if (form == null) {
            msg(sender, "Unknown form '" + args[1] + "'. Forms: small, big", NamedTextColor.RED);
            return;
        }

        Player target;
        if (args.length >= 3) {
            if (!sender.hasPermission(ADMIN)) {
                msg(sender, "Only admins can change someone else's form.", NamedTextColor.RED);
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                msg(sender, "Player '" + args[2] + "' is not online.", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            msg(sender, "Specify a player: /abyss form <small|big> <player>", NamedTextColor.RED);
            return;
        }

        if (!plugin.abyss().isAbyss(target)) {
            msg(sender, target.getName() + " is not the Abyss. Use /abyss set first.", NamedTextColor.RED);
            return;
        }
        if (!target.equals(sender) && !sender.hasPermission(ADMIN)) {
            msg(sender, "Only admins can change someone else's form.", NamedTextColor.RED);
            return;
        }

        plugin.abyss().setForm(target, form);
        if (!target.equals(sender)) {
            msg(sender, target.getName() + " now has the " + form.description() + " body.", NamedTextColor.GREEN);
        }
    }

    // ------------------------------------------------------------------- help

    private void help(CommandSender sender, String label) {
        msg(sender, "AbyssSpirit commands:", NamedTextColor.GOLD);
        msg(sender, "/" + label + " set <player>  - turn a player into the Abyss", NamedTextColor.WHITE);
        msg(sender, "/" + label + " clear <player>  - turn them back into a normal player", NamedTextColor.WHITE);
        msg(sender, "/" + label + " form <small|big> [player]  - switch between the two bodies", NamedTextColor.WHITE);
        msg(sender, "/" + label + " list  - show who is the Abyss", NamedTextColor.WHITE);
        msg(sender, "/" + label + " reload  - reload config.yml", NamedTextColor.WHITE);
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    // ----------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            out.add("form");
            if (sender.hasPermission(ADMIN)) out.addAll(List.of("set", "clear", "list", "reload"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("form")) {
                for (Form form : Form.values()) out.add(form.id());
            } else if (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("clear")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("form") && sender.hasPermission(ADMIN)) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
