package dev.kotwicha.zflip3;

import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoverDaemon {
    private static final String COVER_TOUCH = "/sys/class/sec/tsp2/input/enabled";
    private static final String COVER_TOUCH_COMMAND = "/sys/class/sec/tsp2/cmd";
    private static final String DOUBLE_TAP_ON = "aot_enable,1";
    private static final String DOUBLE_TAP_OFF = "aot_enable,0";
    private static final String KEY_HELPER = "/data/adb/modules/flip3_cover/coverkeys";
    private static final String VOLUME_UP_DEVICE = "gpio_keys_flex";
    private static final String COVER_BACKLIGHT = "/sys/class/backlight/panel1-backlight/brightness";
    private static final File COMMAND_DIR = new File("/data/user_de/0/dev.kotwicha.zflip3/files");
    private static final File BRIGHTNESS_FILE = new File(COMMAND_DIR, "brightness");
    private static final File COMMAND_FILE = new File(COMMAND_DIR, "command");
    private static final String PACKAGE = "dev.kotwicha.zflip3";
    private static final long COMMAND_POLL_MS = 80;
    private static final int SLEEP_REASON_TIMEOUT = 2;
    private static final String ACTIVITY = "dev.kotwicha.zflip3/.CoverActivity";
    private static final String LID_DEVICE = "flip";
    private static final String POWER_DEVICE = "qpnp_pon_flex";
    private static final String TOUCH_DEVICE = "sec_touchscreen2";
    private static final int EVENT_SIZE = 24;
    private static final int EV_KEY = 1;
    private static final int EV_SW = 5;
    private static final int SW_LID = 0;
    private static final int KEY_POWER = 116;
    private static final int KEY_WAKEUP = 143;
    private static final int WAKE_REASON_POWER_BUTTON = 1;
    private static final int WAKE_REASON_GESTURE = 4;
    private static final int WAKE_REASON_APPLICATION = 2;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    private static final int SLEEP_FLAG_NO_DOZE = 1;
    private static final int MAIN_DISPLAY = 0;
    private static final long MAIN_SLEEP_DELAY_MS = 250;
    private static final long DISPLAY_POLL_MS = 100;
    private static final int DISPLAY_POLL_TRIES = 30;

    private final ScheduledExecutorService actions = Executors.newSingleThreadScheduledExecutor();
    private final Object power;
    private final Method wakeUp;
    private final Method goToSleep;
    private final Method isInteractive;
    private final Object window;
    private final Method isKeyguardLocked;
    private volatile boolean folded;
    private volatile int coverDisplay = -1;
    private File powerDevice;
    private File volumeUpDevice;
    private Process keyHelper;
    private final AtomicBoolean volumeQueued = new AtomicBoolean();
    private long brightnessStamp;
    private long commandStamp;

    private CoverDaemon() throws Exception {
        power = service("power", "android.os.IPowerManager$Stub");
        Class<?> powerClass = Class.forName("android.os.IPowerManager");
        wakeUp = powerClass.getMethod("wakeUpWithDisplayId", long.class, int.class, String.class, String.class, int.class);
        goToSleep = powerClass.getMethod("goToSleepWithDisplayId", int.class, long.class, int.class, int.class);
        isInteractive = powerClass.getMethod("isDisplayInteractive", int.class);
        window = service("window", "android.view.IWindowManager$Stub");
        Class<?> windowClass = Class.forName("android.view.IWindowManager");
        isKeyguardLocked = windowClass.getMethod("isKeyguardLocked");
    }

    public static void main(String[] args) throws Exception {
        new CoverDaemon().run();
    }

    private static Object service(String name, String stub) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private void run() throws Exception {
        File lid = findDevice(LID_DEVICE);
        File powerKey = findDevice(POWER_DEVICE);
        File touch = findDevice(TOUCH_DEVICE);
        if (lid == null || powerKey == null || touch == null) {
            throw new IOException("Input devices not found: lid=" + lid + " power=" + powerKey + " touch=" + touch);
        }
        powerDevice = powerKey;
        volumeUpDevice = findDevice(VOLUME_UP_DEVICE);
        log("Watching lid " + lid + ", power key " + powerKey + " and cover touch " + touch);
        grantAccess();
        brightnessStamp = BRIGHTNESS_FILE.lastModified();
        commandStamp = COMMAND_FILE.lastModified();
        actions.scheduleWithFixedDelay(this::pollCommands, COMMAND_POLL_MS, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        if ("0".equals(firstLine("cmd", "device_state", "print-state"))) {
            actions.execute(this::onClosed);
        }
        Thread lidThread = new Thread(() -> readEvents(lid, this::onLidEvent), "lid");
        Thread powerThread = new Thread(() -> readEvents(powerKey, this::onPowerEvent), "power");
        Thread touchThread = new Thread(() -> readEvents(touch, this::onTouchEvent), "touch");
        lidThread.start();
        powerThread.start();
        touchThread.start();
        lidThread.join();
        powerThread.join();
        touchThread.join();
    }

    private interface EventHandler {
        void onEvent(int type, int code, int value);
    }

    private void readEvents(File device, EventHandler handler) {
        byte[] raw = new byte[EVENT_SIZE];
        ByteBuffer event = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        try (DataInputStream in = new DataInputStream(new FileInputStream(device))) {
            while (true) {
                in.readFully(raw);
                handler.onEvent(event.getShort(16) & 0xFFFF, event.getShort(18) & 0xFFFF, event.getInt(20));
            }
        } catch (IOException failed) {
            log("Input stream ended for " + device + ": " + failed);
            System.exit(1);
        }
    }

    private void onLidEvent(int type, int code, int value) {
        if (type == EV_SW && code == SW_LID) {
            actions.execute(value == 1 ? this::onClosed : this::onOpened);
        }
    }

    private void onPowerEvent(int type, int code, int value) {
        if (type == EV_KEY && code == KEY_POWER && value == 1 && folded) {
            actions.execute(this::onPowerPressed);
        }
    }

    private void onTouchEvent(int type, int code, int value) {
        if (type == EV_KEY && code == KEY_WAKEUP && value == 1 && folded) {
            actions.execute(this::onDoubleTap);
        }
    }

    private void grantAccess() {
        lines("pm", "grant", PACKAGE, "android.permission.WRITE_SECURE_SETTINGS");
        lines("cmd", "notification", "allow_listener", PACKAGE + "/" + PACKAGE + ".CoverNotificationListener");
        lines("cmd", "notification", "allow_dnd", PACKAGE);
        lines("pm", "grant", PACKAGE, "android.permission.READ_CALENDAR");
        grantWidgetBinding();
    }

    private static void grantWidgetBinding() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "appwidget");
            Class<?> stub = Class.forName("com.android.internal.appwidget.IAppWidgetService$Stub");
            Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> serviceClass = Class.forName("com.android.internal.appwidget.IAppWidgetService");
            serviceClass.getMethod("setBindAppWidgetPermission", String.class, int.class, boolean.class).invoke(service, PACKAGE, 0, true);
            boolean granted = (boolean) serviceClass.getMethod("hasBindAppWidgetPermission", String.class, int.class).invoke(service, PACKAGE, 0);
            log("Widget binding granted: " + granted);
        } catch (ReflectiveOperationException failed) {
            log("Could not grant widget binding: " + failed);
        }
    }

    private void pollCommands() {
        long brightness = BRIGHTNESS_FILE.lastModified();
        long command = COMMAND_FILE.lastModified();
        boolean newBrightness = brightness != brightnessStamp;
        boolean newCommand = command != commandStamp;
        brightnessStamp = brightness;
        commandStamp = command;
        if (!folded) {
            return;
        }
        if (newBrightness && brightness != 0) {
            applyBrightness();
        }
        if (newCommand && command != 0) {
            runCommand(readFile(COMMAND_FILE));
        }
    }

    private void applyBrightness() {
        String value = readFile(BRIGHTNESS_FILE);
        if (value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
            writeFile(COVER_BACKLIGHT, value);
        }
    }

    private void runCommand(String line) {
        if (line == null) {
            return;
        }
        String[] parts = line.trim().split(" ");
        String name = parts[parts.length - 1];
        log("Command " + name);
        switch (name) {
            case "sleep":
                int display = coverDisplay;
                if (display >= 0) {
                    call(goToSleep, power, display, SystemClock.uptimeMillis(), SLEEP_REASON_TIMEOUT, SLEEP_FLAG_NO_DOZE);
                }
                break;
            case "wake_alert":
                int alertDisplay = coverDisplay;
                if (alertDisplay >= 0 && !Boolean.TRUE.equals(call(isInteractive, power, alertDisplay))) {
                    wakeCover(alertDisplay, WAKE_REASON_APPLICATION, "flip3cover:notification");
                    actions.schedule(this::keepMainAsleep, MAIN_SLEEP_DELAY_MS, TimeUnit.MILLISECONDS);
                }
                break;
            case "wifi_on":
                lines("svc", "wifi", "enable");
                break;
            case "wifi_off":
                lines("svc", "wifi", "disable");
                break;
            case "bluetooth_on":
                lines("svc", "bluetooth", "enable");
                break;
            case "bluetooth_off":
                lines("svc", "bluetooth", "disable");
                break;
            case "airplane_on":
                lines("cmd", "connectivity", "airplane-mode", "enable");
                break;
            case "airplane_off":
                lines("cmd", "connectivity", "airplane-mode", "disable");
                break;
            default:
                log("Ignored unknown command");
                break;
        }
    }

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath())).trim();
        } catch (IOException failed) {
            return null;
        }
    }

    private void startKeyHelper() {
        stopKeyHelper();
        if (!new File(KEY_HELPER).canExecute() || volumeUpDevice == null) {
            log("Key helper unavailable, volume keys stay unswapped");
            return;
        }
        try {
            Process helper = new ProcessBuilder(KEY_HELPER, powerDevice.getPath(), volumeUpDevice.getPath()).redirectErrorStream(true).start();
            keyHelper = helper;
            Thread reader = new Thread(() -> readKeyHelper(helper), "keys");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException failed) {
            log("Could not start key helper: " + failed);
        }
    }

    private void readKeyHelper(Process helper) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(helper.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("power".equals(line)) {
                    if (folded) {
                        actions.execute(this::onPowerPressed);
                    }
                } else if ("volume".equals(line)) {
                    if (folded && volumeQueued.compareAndSet(false, true)) {
                        actions.execute(this::onVolumePressed);
                    }
                } else {
                    log("Key helper: " + line);
                }
            }
        } catch (IOException ended) {
            log("Key helper stream ended");
        }
    }

    private void stopKeyHelper() {
        if (keyHelper != null) {
            keyHelper.destroy();
            keyHelper = null;
        }
    }

    private void onClosed() {
        if (folded) {
            return;
        }
        folded = true;
        startKeyHelper();
        int display = waitForCoverDisplay();
        if (display < 0) {
            log("Cover display did not appear");
            return;
        }
        coverDisplay = display;
        writeFile(COVER_TOUCH, "1");
        writeFile(COVER_TOUCH_COMMAND, DOUBLE_TAP_ON);
        applyBrightness();
        showLauncher(display);
        log("Folded: launcher on display " + display + ", keyguard locked " + call(isKeyguardLocked, window));
    }

    private void onOpened() {
        if (!folded) {
            return;
        }
        folded = false;
        stopKeyHelper();
        coverDisplay = -1;
        writeFile(COVER_TOUCH_COMMAND, DOUBLE_TAP_OFF);
        writeFile(COVER_TOUCH, "0");
        log("Unfolded");
    }

    private void onPowerPressed() {
        int display = coverDisplay;
        if (display < 0) {
            return;
        }
        boolean coverOn = Boolean.TRUE.equals(call(isInteractive, power, display));
        if (coverOn) {
            call(goToSleep, power, display, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, SLEEP_FLAG_NO_DOZE);
        } else {
            wakeCover(display, WAKE_REASON_POWER_BUTTON, "flip3cover:power");
        }
        actions.schedule(this::keepMainAsleep, MAIN_SLEEP_DELAY_MS, TimeUnit.MILLISECONDS);
        log("Power press: cover was " + (coverOn ? "on" : "off"));
    }

    private void onVolumePressed() {
        volumeQueued.set(false);
        int display = coverDisplay;
        if (display < 0) {
            return;
        }
        boolean wasOff = !Boolean.TRUE.equals(call(isInteractive, power, display));
        if (wasOff) {
            wakeDisplay(display, WAKE_REASON_APPLICATION, "flip3cover:volume");
            actions.schedule(this::keepMainAsleep, MAIN_SLEEP_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        firstLine("am", "start", "--display", String.valueOf(display), "-n", ACTIVITY,
            "--ez", CoverCommands.EXTRA_SHOW_VOLUME, "true",
            "--ez", CoverCommands.EXTRA_VOLUME_WOKE, String.valueOf(wasOff));
    }

    private void onDoubleTap() {
        int display = coverDisplay;
        if (display < 0 || Boolean.TRUE.equals(call(isInteractive, power, display))) {
            return;
        }
        wakeCover(display, WAKE_REASON_GESTURE, "flip3cover:doubletap");
        actions.schedule(this::keepMainAsleep, MAIN_SLEEP_DELAY_MS, TimeUnit.MILLISECONDS);
        log("Double tap woke the cover");
    }

    private void wakeCover(int display, int reason, String details) {
        wakeDisplay(display, reason, details);
        showLauncher(display);
    }

    private void wakeDisplay(int display, int reason, String details) {
        call(wakeUp, power, SystemClock.uptimeMillis(), reason, details, PACKAGE, display);
        writeFile(COVER_TOUCH, "1");
        writeFile(COVER_TOUCH_COMMAND, DOUBLE_TAP_ON);
    }

    private void showLauncher(int display) {
        firstLine("am", "start", "--display", String.valueOf(display), "-n", ACTIVITY);
    }

    private void keepMainAsleep() {
        if (folded && Boolean.TRUE.equals(call(isInteractive, power, MAIN_DISPLAY))) {
            call(goToSleep, power, MAIN_DISPLAY, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, SLEEP_FLAG_NO_DOZE);
            log("Put the hidden main screen back to sleep");
        }
        if (folded) {
            writeFile(COVER_TOUCH, "1");
            applyBrightness();
        }
    }

    private int waitForCoverDisplay() {
        for (int attempt = 0; attempt < DISPLAY_POLL_TRIES; attempt++) {
            for (String id : lines("cmd", "display", "get-displays", "-i")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(String.valueOf(MAIN_DISPLAY)) && trimmed.chars().allMatch(Character::isDigit)) {
                    return Integer.parseInt(trimmed);
                }
            }
            SystemClock.sleep(DISPLAY_POLL_MS);
        }
        return -1;
    }

    private static File findDevice(String name) throws IOException {
        File[] entries = new File("/sys/class/input").listFiles((dir, file) -> file.startsWith("event"));
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            File nameFile = new File(entry, "device/name");
            if (nameFile.exists() && name.equals(new String(Files.readAllBytes(nameFile.toPath())).trim())) {
                return new File("/dev/input", entry.getName());
            }
        }
        return null;
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException failed) {
            log(method.getName() + " failed: " + failed.getCause());
            return null;
        }
    }

    private static void writeFile(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException failed) {
            log("Could not write " + path + ": " + failed);
        }
    }

    private static String firstLine(String... command) {
        List<String> output = lines(command);
        return output.isEmpty() ? "" : output.get(0).trim();
    }

    private static List<String> lines(String... command) {
        List<String> output = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException failed) {
            log("Could not run " + command[0] + ": " + failed);
        }
        return output;
    }

    private static void log(String message) {
        System.out.println(SystemClock.uptimeMillis() + " " + message);
    }
}
