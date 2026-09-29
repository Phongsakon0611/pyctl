package phong.pyctl;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.python.util.PythonInterpreter;

import java.nio.file.Files;
import java.nio.file.Path;

public class PyCtlClient implements ClientModInitializer {
    static final String[] NAMES = {"w", "a", "s", "d", "space", "shift", "sprint", "attack", "use"};
    static final boolean[] want = new boolean[NAMES.length];
    static final boolean[] was = new boolean[NAMES.length];
    static volatile Thread running;
    static Path dir;

    public static class Api {
        public void key(String k, int v) {
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].equals(k)) want[i] = v != 0;
            }
        }

        public void sleep(double sec) throws InterruptedException {
            Thread.sleep((long) (sec * 1000));
        }

        public void turn(double yaw, double pitch) {
            MinecraftClient c = MinecraftClient.getInstance();
            c.execute(() -> {
                if (c.player == null) return;
                c.player.setYaw(c.player.getYaw() + (float) yaw);
                float p = c.player.getPitch() + (float) pitch;
                c.player.setPitch(Math.max(-90f, Math.min(90f, p)));
            });
        }

        public void say(String msg) {
            msg(msg);
        }
    }

    static void msg(String s) {
        MinecraftClient c = MinecraftClient.getInstance();
        c.execute(() -> {
            if (c.player != null) c.player.sendMessage(Text.literal(s), false);
        });
    }

    static void clearKeys() {
        for (int i = 0; i < NAMES.length; i++) want[i] = false;
    }

    static void stop() {
        Thread t = running;
        if (t != null) t.interrupt();
        clearKeys();
    }

    static void run(String name) {
        stop();
        Path f = dir.resolve(name + ".py");
        if (!Files.exists(f)) {
            msg("[py] ไม่พบไฟล์ " + f.getFileName());
            return;
        }
        Thread t = new Thread(() -> {
            try {
                System.setProperty("python.import.site", "false");
                System.setProperty("python.cachedir.skip", "true");
                PythonInterpreter py = new PythonInterpreter();
                py.set("mc", new Api());
                py.execfile(f.toString());
                msg("[py] " + name + " จบแล้ว");
            } catch (Throwable e) {
                e.printStackTrace();
                String m = String.valueOf(e.getMessage());
                msg("[py] error: " + (m.length() > 200 ? m.substring(0, 200) : m));
            } finally {
                clearKeys();
            }
        }, "py-script");
        t.setDaemon(true);
        running = t;
        t.start();
    }

    @Override
    public void onInitializeClient() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("pyscripts");
        try {
            Files.createDirectories(dir);
            Path sample = dir.resolve("walk.py");
            if (!Files.exists(sample)) {
                Files.writeString(sample,
                    "mc.say('start')\n" +
                    "mc.key('w', 1)\n" +
                    "mc.sleep(3)\n" +
                    "mc.key('w', 0)\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("py")
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    stop();
                    return 1;
                }))
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        run(StringArgumentType.getString(ctx, "name"));
                        return 1;
                    })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            GameOptions o = client.options;
            KeyBinding[] keys = {o.forwardKey, o.leftKey, o.backKey, o.rightKey,
                o.jumpKey, o.sneakKey, o.sprintKey, o.attackKey, o.useKey};
            for (int i = 0; i < keys.length; i++) {
                if (want[i]) keys[i].setPressed(true);
                else if (was[i]) keys[i].setPressed(false);
                was[i] = want[i];
            }
        });
    }
}
