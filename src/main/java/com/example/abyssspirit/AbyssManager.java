package com.example.abyssspirit;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Abyss: a player turned into a hidden spirit with /abyss set <name>.
 *
 * Other players can't see the spirit's body. They see one of two bodies made of black and
 * dark gray particles:
 *   small: a little floating orb
 *   big:   a great turning spiral, dense at the center, with arms curling outward
 *
 * When the Abyss talks, nobody sees a normal chat line. The words appear above the hotbar
 * as a purple to light purple gradient. Normal chat is shown to everyone, /msg only to the
 * person named.
 */
public final class AbyssManager implements Listener {

    private static final List<String> MSG_COMMANDS = List.of("msg", "tell", "w", "whisper");
    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;

    /** How tightly the spiral arms curl (radians per block of distance from the center). */
    private static final double TWIST = 0.9;

    private static final TextColor PURPLE = TextColor.color(0x9B30FF);
    private static final TextColor LIGHT_PURPLE = TextColor.color(0xE0B0FF);

    // small body
    private static final Particle.DustOptions SMALL_BLACK = new Particle.DustOptions(Color.fromRGB(8, 8, 8), 1.3f);
    private static final Particle.DustOptions SMALL_GRAY = new Particle.DustOptions(Color.fromRGB(125, 125, 125), 1.0f);

    // big body: black and dark gray only
    private static final Particle.DustOptions BIG_BLACK = new Particle.DustOptions(Color.fromRGB(6, 6, 6), 1.9f);
    private static final Particle.DustOptions BIG_DARK = new Particle.DustOptions(Color.fromRGB(70, 70, 70), 1.5f);
    private static final Particle.DustOptions BIG_SPECK = new Particle.DustOptions(Color.fromRGB(70, 70, 70), 0.8f);

    private final AbyssSpirit plugin;
    private final NamespacedKey abyssKey;
    private final NamespacedKey formKey;
    /** Where CustomLockItem used to store this, so existing spirits carry over. */
    private final NamespacedKey legacyKey;

    /** online players who are currently the Abyss, and which body they have */
    private final Map<UUID, Form> active = new ConcurrentHashMap<>(); // read by the async chat thread
    /** the repeating "keep the words on screen" task for each viewer */
    private final Map<UUID, BukkitTask> speech = new HashMap<>();

    private BukkitTask task;
    private int tick;

    public AbyssManager(AbyssSpirit plugin) {
        this.plugin = plugin;
        this.abyssKey = new NamespacedKey(plugin, "abyss");
        this.formKey = new NamespacedKey(plugin, "abyss_form");
        this.legacyKey = NamespacedKey.fromString("customlockitem:wisp");
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            load(player);
        }
        hideEveryone();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 2L, 2L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        speech.values().forEach(BukkitTask::cancel);
        speech.clear();
        for (UUID id : active.keySet()) {
            Player spirit = Bukkit.getPlayer(id);
            if (spirit != null) revealToAll(spirit);
        }
        active.clear();
    }

    // ------------------------------------------------------- set / clear / form

    public boolean isAbyss(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    public Form formOf(Player player) {
        return active.get(player.getUniqueId());
    }

    public void set(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(abyssKey, PersistentDataType.STRING, "abyss");
        data.set(formKey, PersistentDataType.STRING, Form.SMALL.id());
        active.put(player.getUniqueId(), Form.SMALL);
        hideFromAll(player);
        player.sendActionBar(Component.text("You are now the Abyss.", NamedTextColor.YELLOW));
    }

    public boolean clear(Player player) {
        boolean was = active.remove(player.getUniqueId()) != null;
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.remove(abyssKey);
        data.remove(formKey);
        if (legacyKey != null) data.remove(legacyKey);
        revealToAll(player);
        return was;
    }

    public void setForm(Player player, Form form) {
        if (!isAbyss(player)) return;
        active.put(player.getUniqueId(), form);
        player.getPersistentDataContainer().set(formKey, PersistentDataType.STRING, form.id());

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 0.5f);
        player.sendActionBar(Component.text("The Abyss takes its " + form.description() + " form.",
                NamedTextColor.GRAY));
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, Form> entry : active.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) names.add(player.getName() + " (" + entry.getValue().id() + ")");
        }
        return names;
    }

    /** Reads a player's saved state when they join (or when the plugin starts). */
    private void load(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();

        boolean saved = data.has(abyssKey, PersistentDataType.STRING);
        boolean legacy = legacyKey != null && data.has(legacyKey, PersistentDataType.STRING);
        if (!saved && !legacy) return;

        if (legacy) {
            data.set(abyssKey, PersistentDataType.STRING, "abyss");
            data.remove(legacyKey);
        }

        Form form = Form.fromInput(data.get(formKey, PersistentDataType.STRING));
        active.put(player.getUniqueId(), form == null ? Form.SMALL : form);
    }

    // ------------------------------------------------------- hiding the body

    private void hideFromAll(Player spirit) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(spirit)) viewer.hideEntity(plugin, spirit);
        }
    }

    private void revealToAll(Player spirit) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(spirit)) viewer.showEntity(plugin, spirit);
        }
    }

    private void hideEveryone() {
        for (UUID id : active.keySet()) {
            Player spirit = Bukkit.getPlayer(id);
            if (spirit != null) hideFromAll(spirit);
        }
    }

    // ------------------------------------------------------------ the bodies

    private void run() {
        tick++;

        // Once a second, make sure nobody can see a spirit's real body (covers joins, respawns, world changes).
        if (tick % 10 == 0) hideEveryone();

        for (Map.Entry<UUID, Form> entry : active.entrySet()) {
            Player spirit = Bukkit.getPlayer(entry.getKey());
            if (spirit == null || spirit.isDead()) continue;

            List<Player> viewers = viewersOf(spirit);
            if (viewers.isEmpty()) continue;

            if (entry.getValue() == Form.BIG) {
                if (tick % 2 == 0) drawBig(spirit, viewers); // the big body is drawn every 4 ticks to keep traffic low
            } else {
                drawSmall(spirit, viewers);
            }
        }
    }

    /** Players near enough to see the spirit. The spirit never sees its own body. */
    private List<Player> viewersOf(Player spirit) {
        Location base = spirit.getLocation();
        List<Player> viewers = new ArrayList<>();
        for (Player viewer : base.getWorld().getPlayers()) {
            if (viewer.equals(spirit)) continue;
            if (viewer.getLocation().distanceSquared(base) > VIEW_DISTANCE_SQUARED) continue;
            viewers.add(viewer);
        }
        return viewers;
    }

    /** The small body: a little orb of black and gray that bobs gently. */
    private void drawSmall(Player spirit, List<Player> viewers) {
        double radius = clamp(plugin.getConfig().getDouble("small_body.radius", 0.5), 0.2, 2.0);
        double bob = Math.sin(tick * 0.15) * 0.12;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        Location base = spirit.getLocation();
        double cx = base.getX();
        double cy = base.getY() + 1.0 + bob;
        double cz = base.getZ();

        for (int i = 0; i < 14; i++) {
            double u = random.nextDouble() * 2.0 - 1.0;
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double r = radius * (0.5 + 0.5 * random.nextDouble());
            double s = Math.sqrt(1.0 - u * u);

            puff(viewers, cx + r * s * Math.cos(angle), cy + r * u, cz + r * s * Math.sin(angle),
                    i % 2 == 0 ? SMALL_BLACK : SMALL_GRAY);
        }
    }

    /**
     * The big body: a great spiral turning slowly around a dense black core.
     * Arms of black and dark gray specks curl outward, thick at the center and thin at the edges.
     * The whole spiral leans over by tilt_degrees so it can be seen from the side and from below.
     */
    private void drawBig(Player spirit, List<Player> viewers) {
        FileConfiguration config = plugin.getConfig();
        double radius = clamp(config.getDouble("big_body.radius", 3.0), 1.5, 10.0);
        double tilt = Math.toRadians(clamp(config.getDouble("big_body.tilt_degrees", 50.0), 0.0, 90.0));
        double sinTilt = Math.sin(tilt);
        double cosTilt = Math.cos(tilt);
        int arms = (int) clamp(config.getInt("big_body.arms", 6), 2, 12);
        double speed = clamp(config.getDouble("big_body.spin_speed", 1.0), 0.2, 4.0);

        Location base = spirit.getLocation();
        double cx = base.getX();
        double cy = base.getY() + 1.1 + Math.sin(tick * 0.08) * 0.12;
        double cz = base.getZ();
        double spin = tick * 0.07 * speed;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // the dense core
        for (int i = 0; i < 16; i++) {
            double u = random.nextDouble() * 2.0 - 1.0;
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double r = 0.55 * Math.cbrt(random.nextDouble());
            double s = Math.sqrt(1.0 - u * u);

            puff(viewers, cx + r * s * Math.cos(angle), cy + r * u, cz + r * s * Math.sin(angle),
                    i % 3 == 0 ? BIG_DARK : BIG_BLACK);
        }

        // the arms
        int perArm = 9;
        for (int arm = 0; arm < arms; arm++) {
            double armAngle = 2.0 * Math.PI * arm / arms;

            for (int j = 0; j < perArm; j++) {
                double f = (j + random.nextDouble()) / perArm;           // 0 = near the core, 1 = the edge
                double r = 0.5 + Math.pow(f, 1.2) * (radius - 0.5);
                double angle = armAngle + spin - r * TWIST + (random.nextDouble() - 0.5) * 0.3;
                double thickness = 0.4 * (1.0 - 0.6 * f);

                // a spot on the flat spiral, then leaned over by the tilt
                double flatX = Math.cos(angle) * r;
                double flatY = (random.nextDouble() * 2.0 - 1.0) * thickness;
                double flatZ = Math.sin(angle) * r;

                double x = cx + flatX;
                double y = cy + flatY * cosTilt - flatZ * sinTilt;
                double z = cz + flatY * sinTilt + flatZ * cosTilt;

                puff(viewers, x, y, z, j % 3 == 0 ? BIG_DARK : BIG_BLACK);
                if (j % 2 == 0) {
                    puff(viewers, x + (random.nextDouble() - 0.5) * 0.5, y + (random.nextDouble() - 0.5) * 0.3,
                            z + (random.nextDouble() - 0.5) * 0.5, BIG_SPECK);
                }
            }
        }
    }

    private static void puff(List<Player> viewers, double x, double y, double z, Particle.DustOptions color) {
        for (Player viewer : viewers) {
            viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, color);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------- talking

    /** Normal chat from the Abyss: cancelled, then shown above everyone's hotbar. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player speaker = event.getPlayer();
        if (!active.containsKey(speaker.getUniqueId())) return;

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[Abyss] " + text);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                show(viewer, text);
            }
        });
    }

    /** /msg, /tell, /w and /whisper from the Abyss: shown only to the person named. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player speaker = event.getPlayer();
        if (!active.containsKey(speaker.getUniqueId())) return;

        String[] parts = event.getMessage().trim().split("\\s+", 3);
        String label = parts[0].substring(1).toLowerCase(Locale.ROOT);
        if (label.startsWith("minecraft:")) label = label.substring("minecraft:".length());
        if (!MSG_COMMANDS.contains(label) || parts.length < 3) return; // too short: normal usage message

        event.setCancelled(true);

        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null) {
            speaker.sendMessage(Component.text("No player named '" + parts[1] + "' is online.", NamedTextColor.RED));
            return;
        }

        String text = parts[2];
        plugin.getLogger().info("[Abyss -> " + target.getName() + "] " + text);
        show(target, text);
        speaker.sendMessage(Component.text("You whisper to " + target.getName() + ": " + text,
                NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /**
     * The first "shown" letters of the text, colored from purple to light purple.
     * The colors are worked out from the whole text, so they do not shift while it types out.
     */
    private static Component gradient(int[] letters, int shown) {
        Component out = Component.empty();
        for (int i = 0; i < shown; i++) {
            float t = letters.length <= 1 ? 0f : i / (float) (letters.length - 1);
            out = out.append(Component.text(new String(Character.toChars(letters[i])),
                    TextColor.lerp(t, PURPLE, LIGHT_PURPLE)));
        }
        return out;
    }

    /**
     * Shows text above the hotbar one letter at a time, as if the Abyss were talking,
     * in a purple to light purple gradient. Once it is fully typed it stays up long enough to
     * read (action bar text fades after about 3 seconds, so it is sent again every 1.5 seconds).
     */
    private void show(Player viewer, String text) {
        UUID id = viewer.getUniqueId();
        BukkitTask old = speech.remove(id);
        if (old != null) old.cancel();

        int[] letters = text.codePoints().toArray();
        if (letters.length == 0) return;

        int perLetter = Math.max(0, Math.min(20, plugin.getConfig().getInt("speech.ticks_per_letter", 2)));
        int hold = Math.max(60, Math.min(160, 40 + letters.length * 2));

        BukkitTask task = new BukkitRunnable() {
            int shown = 0;
            int wait = perLetter; // ticks until the next letter
            int holdLeft = hold;
            int sinceSent = 0;

            @Override
            public void run() {
                if (!viewer.isOnline()) {
                    stop();
                    return;
                }

                if (shown < letters.length) {
                    if (perLetter == 0) {
                        shown = letters.length;
                    } else if (--wait > 0) {
                        return;
                    } else {
                        wait = perLetter;
                        do {
                            shown++;
                        } while (shown < letters.length && Character.isWhitespace(letters[shown - 1]));
                    }
                    viewer.sendActionBar(gradient(letters, shown));
                    sinceSent = 0;
                    return;
                }

                // Fully typed: keep it on screen for a while.
                holdLeft--;
                if (holdLeft <= 0) {
                    stop();
                    return;
                }
                if (++sinceSent >= 30) {
                    viewer.sendActionBar(gradient(letters, letters.length));
                    sinceSent = 0;
                }
            }

            private void stop() {
                cancel();
                speech.remove(id);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        speech.put(id, task);
    }

    // ------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());

        // Hide every spirit from the player who just joined, and hide this player if they are the Abyss.
        Bukkit.getScheduler().runTask(plugin, this::hideEveryone);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        active.remove(id);
        BukkitTask old = speech.remove(id);
        if (old != null) old.cancel();
    }
}
