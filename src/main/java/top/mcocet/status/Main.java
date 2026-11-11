package top.mcocet.status;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.Xinbot;
import xin.bbtt.mcbot.jLine.CLI;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * XinSta - a configurable status line for the XinBot console.
 *
 * <p>Reserves one JLine status row and keeps a live summary pinned to the terminal's top or bottom
 * edge while log lines keep scrolling in the remaining area.
 *
 * <p>The layout lives in {@code XinSta.yml} next to {@code config.conf}:
 *
 * <pre>
 * statusBar: true          # false hides the bar
 * position: top            # top | bottom
 * separator: " | "
 * prefix: "XinSta"
 * fields: [prefix, version, account, server, state, players, queue]
 * # format: "{prefix} {version} | {account} | {server} | {state}"
 * </pre>
 *
 * <p>Segments: {@code prefix, version, account, server, state, players, queue, plugins, memory, time}.
 * In the default layout empty segments are skipped; in a {@code format} template they expand to an
 * empty string. A template wins over {@code fields} when both are present.
 */
public final class Main implements Plugin {
    private static final String WIDGET = "xinsta-status-bar-redraw";
    private static final long REFRESH_MILLIS = 1000L;
    private static final Object RENDER_LOCK = new Object();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String DEFAULT_SEPARATOR = " | ";
    private static final String DEFAULT_PREFIX = "XinSta";
    private static final List<String> DEFAULT_FIELDS =
            List.of("prefix", "version", "account", "server", "state", "players", "queue");

    private enum Position {
        TOP,
        BOTTOM
    }

    /** Immutable snapshot of XinSta.yml; swapped whole so a render never sees a half-applied edit. */
    private record Settings(boolean enabled, Position position, String separator, String prefix,
                            String format, List<String> fields) {
        static Settings defaults() {
            return new Settings(true, Position.TOP, DEFAULT_SEPARATOR, DEFAULT_PREFIX, "", DEFAULT_FIELDS);
        }
    }

    private static volatile Settings settings = Settings.defaults();
    private static volatile LineReader activeReader;
    private static volatile String currentText = " XinSta | STARTING ";
    private static volatile String lastSignature;
    private static Status reservation;
    private static ScheduledExecutorService refresher;
    private static Thread shutdownHook;

    @Override
    public void onLoad() {
        settings = readSettings();
        if (settings.enabled()) {
            install(CLI.getLineReader());
        }
    }

    @Override
    public void onEnable() {
        // Called on every connection: pick up an edited XinSta.yml before redrawing.
        settings = readSettings();
        lastSignature = null;

        if (!settings.enabled()) {
            close();
            return;
        }

        if (activeReader == null) {
            install(CLI.getLineReader());
        }
    }

    @Override
    public void onDisable() {
        // XinBot disables plugins on every disconnect; the bar is meant to stay put through that.
        // It is removed on unload (plugin reload or bot shutdown) only.
    }

    @Override
    public void onUnload() {
        close();
    }

    // ==================== configuration ====================

    /** {@code XinSta.yml} lives in the same directory as the bot's own config file. */
    private static File configFile() {
        String path = Xinbot.configPath == null ? Xinbot.defaultConfigPath : Xinbot.configPath;
        File botConfig = new File(path).getAbsoluteFile();
        File parent = botConfig.getParentFile();
        return new File(parent == null ? new File(".") : parent, "XinSta.yml");
    }

    private static Settings readSettings() {
        File file = configFile();
        seed(file);
        Settings defaults = Settings.defaults();

        try {
            Config config = ConfigFactory.parseFile(file);
            boolean enabled = !config.hasPath("statusBar") || config.getBoolean("statusBar");
            Position position = parsePosition(config.hasPath("position") ? config.getString("position") : "top");
            String separator = config.hasPath("separator") ? config.getString("separator") : defaults.separator();
            String prefix = config.hasPath("prefix") ? config.getString("prefix") : defaults.prefix();
            String format = config.hasPath("format") ? config.getString("format").trim() : "";
            List<String> fields = config.hasPath("fields")
                    ? config.getStringList("fields").stream().map(value -> value.toLowerCase(Locale.ROOT)).toList()
                    : defaults.fields();
            return new Settings(enabled, position, separator, prefix, format, fields);
        } catch (Exception ignored) {
            return defaults;
        }
    }

    private static Position parsePosition(final String value) {
        return "bottom".equalsIgnoreCase(value.trim()) ? Position.BOTTOM : Position.TOP;
    }

    private static void seed(final File file) {
        if (file.isFile()) {
            return;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.writeString(file.toPath(), """
                    # XinSta console status bar
                    # statusBar: false hides the bar. A plugin reload or the next connection re-reads
                    # this file, so no restart is needed.
                    statusBar: true

                    # Where the bar sits: top or bottom.
                    position: top

                    # Default layout: the fields below, joined by `separator`. Empty values are skipped.
                    # Supported: prefix, version, account, server, state, players, queue, plugins, memory, time
                    separator: " | "
                    prefix: "XinSta"
                    fields: [prefix, version, account, server, state, players, queue]

                    # Optional full template; when set it wins over `fields`.
                    # Placeholders: {prefix} {version} {account} {server} {state}
                    #               {players} {queue} {plugins} {memory} {time}
                    # format: "{prefix} {version} | {account} | {server} | {state}"
                    """, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A read-only working directory just means the built-in defaults are used.
        }
    }

    // ==================== status bar installation ====================

    /**
     * Reserves one JLine status row and pins the scrolling region to the opposite edge. Safe to call
     * repeatedly: a previous installation is torn down first. Does nothing on tiny or non-ANSI terminals.
     */
    private static void install(final LineReader reader) {
        close();
        if (!settings.enabled() || reader == null || !supports(reader.getTerminal())) {
            return;
        }

        Terminal terminal = reader.getTerminal();
        Size size = terminal.getSize();
        if (size.getRows() < 4 || size.getColumns() < 16) {
            return;
        }

        Status status = Status.getStatus(terminal);
        if (status == null) {
            return;
        }

        synchronized (RENDER_LOCK) {
            // JLine subtracts one row from its editable display while this status exists. Suspending its
            // own redraw keeps that accounting without letting it restore its usual bottom scroll region.
            status.update(List.of(new AttributedString("")), false);
            status.suspend();
            reservation = status;
            activeReader = reader;
            lastSignature = null;
            reader.getWidgets().put(WIDGET, Main::redrawFromWidget);

            // DECSTBM (change_scroll_region) moves the cursor home on most terminals, so wrap it: a
            // top bar must leave the cursor where the last log line ended, above the region's bottom.
            terminal.puts(Capability.save_cursor);
            applyRegion(terminal, size);
            terminal.puts(Capability.restore_cursor);
            // A bottom bar owns the last row, which lies outside the scrolling region: move the
            // cursor up one row first, or JLine's newline-driven display stops scrolling and pushes
            // all following output off screen.
            if (settings.position() == Position.BOTTOM) {
                terminal.puts(Capability.cursor_address, Math.max(0, size.getRows() - 2), 0);
            }
            currentText = snapshot();
            render();
        }

        startRefresher();
    }

    private static void startRefresher() {
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "XinSta-StatusBar");
            thread.setDaemon(true);
            return thread;
        });
        refresher = service;
        service.scheduleWithFixedDelay(Main::tick, REFRESH_MILLIS, REFRESH_MILLIS, TimeUnit.MILLISECONDS);

        Thread hook = new Thread(Main::close, "XinSta-StatusBar shutdown");
        shutdownHook = hook;
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private static void tick() {
        LineReader reader = activeReader;
        if (reader == null) {
            return;
        }

        currentText = snapshot();

        try {
            reader.callWidget(WIDGET);
        } catch (Exception ignored) {
            // Between readLine calls there is no widget context; the next tick redraws.
        }
    }

    private static boolean redrawFromWidget() {
        synchronized (RENDER_LOCK) {
            return render();
        }
    }

    private static boolean render() {
        LineReader reader = activeReader;
        if (reader == null) {
            return false;
        }

        Terminal terminal = reader.getTerminal();
        Size size = terminal.getSize();
        if (size.getRows() < 4 || size.getColumns() < 16) {
            return true;
        }

        String signature = settings.position() + ":" + size.getRows() + ":" + size.getColumns() + ":" + currentText;
        if (signature.equals(lastSignature)) {
            return true;
        }

        AttributedString line = fit(currentText, size.getColumns());
        terminal.puts(Capability.save_cursor);
        applyRegion(terminal, size);
        terminal.puts(Capability.cursor_address, statusRow(size), 0);
        terminal.puts(Capability.clr_eol);
        terminal.writer().print(line.toAnsi(terminal));
        terminal.puts(Capability.restore_cursor);
        terminal.flush();
        lastSignature = signature;
        return true;
    }

    private static void applyRegion(final Terminal terminal, final Size size) {
        if (settings.position() == Position.BOTTOM) {
            terminal.puts(Capability.change_scroll_region, 0, Math.max(0, size.getRows() - 2));
        } else {
            terminal.puts(Capability.change_scroll_region, 1, size.getRows() - 1);
        }
    }

    private static int statusRow(final Size size) {
        return settings.position() == Position.BOTTOM ? size.getRows() - 1 : 0;
    }

    private static AttributedString fit(final String text, final int width) {
        AttributedString source = new AttributedString(text);
        AttributedStringBuilder line = new AttributedStringBuilder(width);
        if (source.columnLength() > width) {
            line.append(source.columnSubSequence(0, Math.max(0, width - 1)), AttributedStyle.INVERSE);
            line.append("…", AttributedStyle.INVERSE);
        } else {
            line.append(source, AttributedStyle.INVERSE);
            line.append(" ".repeat(Math.max(0, width - source.columnLength())), AttributedStyle.INVERSE);
        }

        return line.toAttributedString();
    }

    // ==================== content ====================

    private static String snapshot() {
        try {
            return buildSnapshot();
        } catch (Throwable ignored) {
            // A plugin being (un)loaded mid-read must never kill the refresh loop.
            return " XinSta " + Xinbot.VERSION + " ";
        }
    }

    private static String buildSnapshot() {
        Bot bot = Bot.INSTANCE;
        Settings current = settings;
        Map<String, String> values = values(bot, current);

        String text;
        if (!current.format().isEmpty()) {
            text = current.format();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        } else {
            StringBuilder joined = new StringBuilder();
            for (String field : current.fields()) {
                String value = values.get(field);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(current.separator());
                }
                joined.append(value);
            }
            text = joined.toString();
        }

        if (text.isBlank()) {
            text = " XinSta " + Xinbot.VERSION;
        }

        return " " + text.strip() + " ";
    }

    /** One value per supported segment; empty means "nothing to show right now". */
    private static Map<String, String> values(final Bot bot, final Settings current) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("prefix", current.prefix());
        values.put("version", String.valueOf(Xinbot.VERSION));
        values.put("account", nullToEmpty(accountName(bot)));
        values.put("server", nullToEmpty(address(bot)));
        values.put("state", state(bot));
        values.put("players", bot.getServer() == Server.Game ? "players " + bot.players.size() : "");
        values.put("queue", bot.getToBeSentMessages().isEmpty() ? "" : "queue " + bot.getToBeSentMessages().size());
        values.put("plugins", "plugins " + bot.getPluginManager().getPlugins().size());
        values.put("memory", memory());
        values.put("time", LocalTime.now().format(TIME_FORMAT));
        return values;
    }

    private static String memory() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        return "mem " + used + "M/" + max + "M";
    }

    private static String accountName(final Bot bot) {
        var protocol = bot.getProtocol();
        if (protocol != null && protocol.getProfile() != null) {
            return protocol.getProfile().getName();
        }

        return null;
    }

    private static String state(final Bot bot) {
        if (!bot.isRunning()) {
            return "STOPPED";
        }

        var session = bot.getSession();
        if (session == null || !session.isConnected()) {
            return "OFFLINE";
        }

        Server server = bot.getServer();
        if (server == Server.Game) {
            return "GAME";
        }
        if (server == Server.Login) {
            return "LOGIN";
        }

        return "CONNECTING";
    }

    private static String address(final Bot bot) {
        var session = bot.getSession();
        SocketAddress remote = session == null ? null : session.getRemoteAddress();

        if (remote == null) {
            var meta = bot.getPluginManager().getMetaPlugin();
            if (meta != null) {
                remote = meta.getServerSocketAddress();
            }
        }

        if (remote == null) {
            return null;
        }

        if (remote instanceof InetSocketAddress inet) {
            return inet.getHostString() + ":" + inet.getPort();
        }

        String value = remote.toString();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static boolean supports(final Terminal terminal) {
        return terminal != null && !(terminal instanceof DumbTerminal)
                && terminal.getStringCapability(Capability.change_scroll_region) != null
                && terminal.getStringCapability(Capability.save_cursor) != null
                && terminal.getStringCapability(Capability.restore_cursor) != null
                && terminal.getStringCapability(Capability.cursor_address) != null
                && terminal.getStringCapability(Capability.clr_eol) != null;
    }

    /** Restores the normal full-screen scrolling region and clears the pinned row. Safe to call repeatedly. */
    private static void close() {
        synchronized (RENDER_LOCK) {
            LineReader reader = activeReader;
            activeReader = null;
            lastSignature = null;

            if (reader != null) {
                reader.getWidgets().remove(WIDGET);
                Terminal terminal = reader.getTerminal();
                if (supports(terminal)) {
                    Size size = terminal.getSize();
                    terminal.puts(Capability.save_cursor);
                    terminal.puts(Capability.change_scroll_region, 0, Math.max(0, size.getRows() - 1));
                    terminal.puts(Capability.cursor_address, 0, 0);
                    terminal.puts(Capability.clr_eol);
                    terminal.puts(Capability.restore_cursor);
                    terminal.flush();
                }
            }

            if (reservation != null) {
                reservation.close();
                reservation = null;
            }
        }

        ScheduledExecutorService service = refresher;
        refresher = null;
        if (service != null) {
            service.shutdownNow();
        }

        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null && Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down; the hook is about to run anyway.
            }
        }
    }
}
