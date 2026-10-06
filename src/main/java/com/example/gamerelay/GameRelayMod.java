package com.example.gamerelay;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(modid = GameRelayMod.MODID, name = "GameRelay", version = "1.6", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class GameRelayMod {
    public static final String MODID = "gamerelay";

    // ===== Chat patterns (edit these if your server words things differently) =====
    private static final Pattern COUNTDOWN = Pattern.compile("^The game (?:starts|is starting) in (\\d+) seconds?!$");
    // "Guild > [RANK] Name [GUILDRANK]: message"
    private static final Pattern GUILD = Pattern.compile("^Guild > (?:\\[[^\\]]+\\] )?(\\w{1,16})(?: \\[[^\\]]+\\])?: (.+)$");

    // ===== Sidebar scoreboard patterns (the lobby scoreboard) =====
    private static final Pattern SB_MAP = Pattern.compile("^Map:\\s*(.+)$");
    private static final Pattern SB_PLAYERS = Pattern.compile("^Players:\\s*(\\d+)/(\\d+)$");
    private static final Pattern SB_TIMER = Pattern.compile("^Starting in (\\d+:\\d+)");
    private static final Pattern SB_MORE = Pattern.compile("^(\\d+) more players? join");

    // Only announce the countdown at these seconds
    private static final int[] ANNOUNCE_AT = {30, 10};

    // Random delay before sending, so that when several people have the mod
    // the first one to fire wins and everyone else sees it in guild chat and cancels.
    private static final int JITTER_MIN_MS = 300;
    private static final int JITTER_RANGE_MS = 1700;

    private final Random random = new Random();
    private boolean enabled = true;
    private boolean debug = false;
    private String lastAnnounced = "";

    // Pending guild message (waiting out its random delay)
    private String pendingText = null;
    private Pattern pendingMatch = null; // if a guild message matches this, we cancel ours
    private long pendingAt = 0;
    private boolean pendingIsQueue = false; // queue replies are rebuilt right before sending so the timer is fresh
    private String pendingMap = null;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new CommandBase() {
            @Override
            public String getCommandName() {
                return "gamerelay";
            }

            @Override
            public String getCommandUsage(ICommandSender sender) {
                return "/gamerelay - toggles GameRelay on/off";
            }

            @Override
            public void processCommand(ICommandSender sender, String[] args) {
                if (args.length > 0 && args[0].equalsIgnoreCase("sidebar")) {
                    List<String> lines = readSidebar();
                    say("Sidebar has " + lines.size() + " lines:");
                    for (String l : lines) say("[" + escape(l) + "]");
                    return;
                }
                if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
                    debug = !debug;
                    say("Debug " + (debug ? "ON" : "OFF"));
                    return;
                }
                enabled = !enabled;
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[GameRelay] "
                        + (enabled ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled")));
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 0;
            }

            @Override
            public boolean canCommandSenderUseCommand(ICommandSender sender) {
                return true;
            }
        });
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world.isRemote) {
            lastAnnounced = "";
            pendingText = null;
            pendingMatch = null;
        }
    }

    private void say(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null)
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[GameRelay] " + EnumChatFormatting.WHITE + text));
    }

    /** Shows hidden or unusual characters as hex codes so we can see what the scoreboard really contains. */
    private static String escape(String in) {
        StringBuilder sb = new StringBuilder();
        for (char ch : in.toCharArray()) {
            if (ch >= 32 && ch < 127) sb.append(ch);
            else sb.append("<").append(Integer.toHexString((int) ch)).append(">");
        }
        return sb.toString();
    }

    /** Reads the right-hand sidebar scoreboard, top to bottom, with colour codes stripped. */
    private List<String> readSidebar() {
        List<String> lines = new ArrayList<String>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return lines;
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1); // 1 = sidebar
        if (obj == null) return lines;
        for (Score s : sb.getSortedScores(obj)) {
            if (s.getPlayerName() == null || s.getPlayerName().startsWith("#")) continue;
            ScorePlayerTeam team = sb.getPlayersTeam(s.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, s.getPlayerName());
            // Hypixel hides emoji characters in the lines (even inside numbers); keep only plain ASCII
            line = StringUtils.stripControlCodes(line).replaceAll("[^\\x20-\\x7E]", "").trim();
            lines.add(line);
        }
        java.util.Collections.reverse(lines); // top-to-bottom, like on screen
        return lines;
    }

    private void scheduleGuildMessage(String text, Pattern cancelIfSeen) {
        pendingText = text;
        pendingMatch = cancelIfSeen;
        pendingIsQueue = false;
        pendingAt = System.currentTimeMillis() + JITTER_MIN_MS + random.nextInt(JITTER_RANGE_MS);
    }

    /** Builds e.g. "Solace 14/100 - starting in 03:52 if 16 more players join" from the lobby scoreboard. Returns null if not in a lobby. */
    private String[] buildQueueText() {
        String map = null;
        String size = null;
        String timer = null;
        String more = null;
        for (String line : readSidebar()) {
            Matcher m = SB_MAP.matcher(line);
            if (m.matches()) map = m.group(1).trim();
            Matcher p = SB_PLAYERS.matcher(line);
            if (p.matches()) size = p.group(1) + "/" + p.group(2);
            Matcher t = SB_TIMER.matcher(line);
            if (t.find()) timer = t.group(1);
            Matcher mo = SB_MORE.matcher(line);
            if (mo.find()) more = mo.group(1);
        }
        if (debug) say("Sidebar check: map=" + map + " size=" + size + " timer=" + timer + " more=" + more);
        if (map == null || size == null) return null; // not in a queue lobby
        String text = map + " " + size;
        if (timer != null) {
            text += " - starting in " + timer;
            if (more != null) text += " if " + more + " more players join";
        }
        return new String[]{map, text};
    }

    private void scheduleQueueReply() {
        String[] built = buildQueueText();
        if (built == null) return;
        scheduleGuildMessage(built[1], Pattern.compile("^" + Pattern.quote(built[0]) + " \\d+/\\d+.*$"));
        pendingIsQueue = true;
        pendingMap = built[0];
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!enabled || event.type == 2) return; // 2 = action bar

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        String text = StringUtils.stripControlCodes(event.message.getUnformattedText()).trim();

        if (debug && text.startsWith("Guild >") && !GUILD.matcher(text).matches())
            say("Guild line didn't match my pattern: " + escape(text));

        // 1) Guild chat: cancel our pending message if someone already said it, and handle "queue"
        Matcher g = GUILD.matcher(text);
        if (g.matches()) {
            String content = g.group(2).trim();
            if (debug) say("Saw guild msg from " + g.group(1) + ": \"" + content + "\"");
            if (pendingText != null && pendingMatch.matcher(content).matches()) {
                pendingText = null;
                pendingMatch = null;
            }
            if ((content.equalsIgnoreCase("queue") || content.equalsIgnoreCase("!queue")) && pendingText == null) {
                scheduleQueueReply();
            }
            return;
        }

        // 2) Countdown: announce only at 30s and 10s
        Matcher c = COUNTDOWN.matcher(text);
        if (c.matches()) {
            int seconds = Integer.parseInt(c.group(1));
            boolean wanted = false;
            for (int s : ANNOUNCE_AT) if (s == seconds) wanted = true;
            if (wanted && !text.equals(lastAnnounced)) {
                lastAnnounced = text;
                scheduleGuildMessage(text, Pattern.compile(Pattern.quote(text)));
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (pendingText != null && System.currentTimeMillis() >= pendingAt) {
            String out = pendingText;
            if (pendingIsQueue) { // refresh so the timer / player count is current
                String[] fresh = buildQueueText();
                out = (fresh != null) ? fresh[1] : null;
            }
            pendingText = null;
            pendingMatch = null;
            pendingIsQueue = false;
            if (out != null) mc.thePlayer.sendChatMessage("/gc " + out);
        }
    }
}
