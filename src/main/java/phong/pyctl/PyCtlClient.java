package phong.pyctl;

import net.minecraft.util.math.BlockPos;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.python.util.PythonInterpreter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PyCtlClient implements ClientModInitializer {
    static final String[] NAMES = {"w", "a", "s", "d", "space", "shift", "sprint", "attack", "use"};
    static final boolean[] want = new boolean[NAMES.length];
    static final boolean[] was = new boolean[NAMES.length];
    static volatile Thread running;
    static Path dir;

    static MinecraftClient mcc() {
        return MinecraftClient.getInstance();
    }

    static <T> T onMain(Supplier<T> s) {
        MinecraftClient c = mcc();
        if (c.isOnThread()) return s.get();
        try {
            return c.submit(s).get();
        } catch (Exception e) {
            return null;
        }
    }

    static double num(Supplier<Double> s) {
        Double v = onMain(s);
        return v == null ? 0 : v;
    }

    static void tap(KeyBinding kb) {
        KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey()));
    }

    static void msg(String s) {
        MinecraftClient c = mcc();
        c.execute(() -> {
            if (c.player != null) c.player.sendMessage(Text.literal(s), false);
        });
    }

    public static class Api {
        // ---- ปุ่มกดค้าง ----
        public void key(String k, int v) {
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].equals(k)) want[i] = v != 0;
            }
        }

        public void sleep(double sec) throws InterruptedException {
            Thread.sleep((long) (sec * 1000));
        }

        // ---- คลิกครั้งเดียว ----
        public void click(String k) {
            MinecraftClient c = mcc();
            c.execute(() -> tap(k.equals("use") ? c.options.useKey : c.options.attackKey));
        }

        public void slot(int n) {
            if (n < 1 || n > 9) return;
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.hotbarKeys[n - 1]));
        }

        public void drop() {
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.dropKey));
        }

        public void swap() {
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.swapHandsKey));
        }

        public void inventory() {
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.inventoryKey));
        }

        public void closeScreen() {
            MinecraftClient c = mcc();
            c.execute(() -> c.setScreen(null));
        }

        // ---- กล้อง ----
        public void turn(double yaw, double pitch) {
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null) return;
                c.player.setYaw(c.player.getYaw() + (float) yaw);
                float p = c.player.getPitch() + (float) pitch;
                c.player.setPitch(Math.max(-90f, Math.min(90f, p)));
            });
        }

        public void setLook(double yaw, double pitch) {
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null) return;
                c.player.setYaw((float) yaw);
                c.player.setPitch((float) Math.max(-90, Math.min(90, pitch)));
            });
        }

        public void lookAt(double x, double y, double z) {
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null) return;
                double dx = x - c.player.getX();
                double dy = y - c.player.getEyeY();
                double dz = z - c.player.getZ();
                double h = Math.sqrt(dx * dx + dz * dz);
                c.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                c.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
            });
        }

        // ---- อ่านข้อมูล ----
        public double x() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : p.getX(); });
        }

        public double y() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : p.getY(); });
        }

        public double z() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : p.getZ(); });
        }

        public double yaw() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : (double) p.getYaw(); });
        }

        public double pitch() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : (double) p.getPitch(); });
        }

        public double health() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : (double) p.getHealth(); });
        }

        public double food() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : (double) p.getHungerManager().getFoodLevel(); });
        }

        public String target() {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                HitResult h = c.crosshairTarget;
                if (h == null || c.world == null) return "none";
                if (h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK)
                    return "block:" + Registries.BLOCK.getId(c.world.getBlockState(b.getBlockPos()).getBlock());
                if (h instanceof EntityHitResult e)
                    return "entity:" + Registries.ENTITY_TYPE.getId(e.getEntity().getType());
                return "none";
            });
            return r == null ? "none" : r;
        }

        // คืนค่า [x, y, z, ระยะห่าง] ของมอนที่ใกล้สุด หรือ None ถ้าไม่มี
        public double[] nearest(double range) {
            return onMain(() -> {
                MinecraftClient c = mcc();
                if (c.player == null || c.world == null) return null;
                List<LivingEntity> list = c.world.getEntitiesByClass(LivingEntity.class,
                    c.player.getBoundingBox().expand(range), e -> e != c.player && e.isAlive());
                LivingEntity best = null;
                double bd = 1e18;
                for (LivingEntity e : list) {
                    double d = e.squaredDistanceTo(c.player);
                    if (d < bd) { bd = d; best = e; }
                }
                if (best == null) return null;
                return new double[]{best.getX(), best.getY() + best.getHeight() / 2.0, best.getZ(), Math.sqrt(bd)};
            });
        }

        // ---- แชท/คำสั่ง ----
        public void say(String s) {
            msg(s);
        }

        public void chat(String s) {
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player != null) c.player.networkHandler.sendChatMessage(s);
            });
        }

        public void command(String s) {
            MinecraftClient c = mcc();
            String cmd = s.startsWith("/") ? s.substring(1) : s;
            c.execute(() -> {
                if (c.player != null) c.player.networkHandler.sendChatCommand(cmd);
            });
        }
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
                String m = e.toString();
                if (m.contains("InterruptedException")) {
                    msg("[py] หยุดแล้ว");
                } else {
                    e.printStackTrace();
                    msg("[py] error: " + (m.length() > 200 ? m.substring(0, 200) : m));
                }
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
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    try (Stream<Path> s = Files.list(dir)) {
                        String names = s.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".py"))
                            .map(n -> n.substring(0, n.length() - 3))
                            .sorted().collect(Collectors.joining(", "));
                        msg("[py] " + names);
                    } catch (Exception e) {
                        msg("[py] อ่านโฟลเดอร์ไม่ได้");
                    }
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
    // ชื่อบล็อกที่พิกัดนี้ เช่น "minecraft:stone" (ใส่เลขจำนวนเต็ม)
        public String block(int x, int y, int z) {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null) return "none";
                return Registries.BLOCK.getId(c.world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
            });
            return r == null ? "none" : r;
        }

        // บล็อกที่อยู่ข้างหน้าตามทิศที่หัน: dist = ระยะ, dy = สูง/ต่ำ (0 = ระดับเท้า, -1 = พื้น, 1 = ระดับหัว)
        public String ahead(int dist, int dy) {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null || c.player == null) return "none";
                BlockPos p = c.player.getBlockPos();
                var d = c.player.getHorizontalFacing();
                BlockPos t = p.add(d.getOffsetX() * dist, dy, d.getOffsetZ() * dist);
                return Registries.BLOCK.getId(c.world.getBlockState(t).getBlock()).toString();
            });
            return r == null ? "none" : r;
        }

        // บล็อกตามชื่อที่ใกล้สุดรอบตัว คืน [x, y, z, ระยะ] (พิกัดกลางบล็อก) หรือ None
        public double[] find(String name, int range) {
            final int rg = Math.min(range, 24);
            return onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null || c.player == null) return null;
                String id = name.contains(":") ? name : "minecraft:" + name;
                BlockPos p = c.player.getBlockPos();
                BlockPos.Mutable m = new BlockPos.Mutable();
                double bd = 1e18;
                int bx = 0, by = 0, bz = 0;
                boolean found = false;
                for (int dx = -rg; dx <= rg; dx++)
                    for (int dy = -rg; dy <= rg; dy++)
                        for (int dz = -rg; dz <= rg; dz++) {
                            m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                            String bid = Registries.BLOCK.getId(c.world.getBlockState(m).getBlock()).toString();
                            if (bid.equals(id)) {
                                double d = dx * dx + dy * dy + dz * dz;
                                if (d < bd) { bd = d; bx = m.getX(); by = m.getY(); bz = m.getZ(); found = true; }
                            }
                        }
                if (!found) return null;
                return new double[]{bx + 0.5, by + 0.5, bz + 0.5, Math.sqrt(bd)};
            });
        }
