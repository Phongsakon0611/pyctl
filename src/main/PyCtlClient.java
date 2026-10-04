package phong.pyctl;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.python.util.PythonInterpreter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PyCtlClient implements ClientModInitializer {
    static final String[] NAMES = {"w", "a", "s", "d", "space", "shift", "sprint", "attack", "use"};
    static final int IDX_W = 0, IDX_SPACE = 4, IDX_ATTACK = 7;
    static final boolean[] want = new boolean[NAMES.length];
    static final boolean[] was = new boolean[NAMES.length];
    static final List<String> chatBuf = Collections.synchronizedList(new ArrayList<>());
    static final ThreadLocal<Integer> myRun = new ThreadLocal<>();
    static final Pattern FILE_LINE = Pattern.compile("File \"([^\"]*)\", line (\\d+)");
    static volatile Thread running;
    static volatile int runId = 0;
    static volatile double safeHp = 6;
    static Path dir;
    static KeyBinding stopKey;

    static class ScriptStop extends RuntimeException {
        ScriptStop() {
            super("ScriptStop");
        }
    }

    static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double f;
        boolean closed;

        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    // ================= ตัวช่วยทั่วไป =================
    static MinecraftClient mcc() {
        return MinecraftClient.getInstance();
    }

    static void chk() {
        Integer id = myRun.get();
        if (id != null && id.intValue() != runId) throw new ScriptStop();
    }

    static <T> T onMain(Supplier<T> s) {
        chk();
        MinecraftClient c = mcc();
        if (c.isOnThread()) return s.get();
        try {
            return c.submit(s).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static double num(Supplier<Double> s) {
        Double v = onMain(s);
        return v == null ? 0 : v;
    }

    static boolean bool(Supplier<Boolean> s) {
        Boolean v = onMain(s);
        return v != null && v;
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

    static void addChat(String s) {
        chatBuf.add(s);
        while (chatBuf.size() > 100) chatBuf.remove(0);
    }

    static String idOf(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).toString();
    }

    static String norm(String n) {
        return n.contains(":") ? n : "minecraft:" + n;
    }

    static void faceTo(double x, double y, double z) {
        var p = mcc().player;
        if (p == null) return;
        double dx = x - p.getX();
        double dy = y - p.getEyeY();
        double dz = z - p.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    static LivingEntity nearestEntity(String type, double range) {
        MinecraftClient c = mcc();
        if (c.player == null || c.world == null) return null;
        List<LivingEntity> list = c.world.getEntitiesByClass(LivingEntity.class,
            c.player.getBoundingBox().expand(range), e -> e != c.player && e.isAlive());
        LivingEntity best = null;
        double bd = 1e18;
        for (LivingEntity e : list) {
            if (type != null && !Registries.ENTITY_TYPE.getId(e.getType()).toString().contains(type)) continue;
            double d = e.squaredDistanceTo(c.player);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    static double[] findEntity(String type, double range) {
        return onMain(() -> {
            LivingEntity e = nearestEntity(type, range);
            if (e == null) return null;
            MinecraftClient c = mcc();
            return new double[]{e.getX(), e.getY() + e.getHeight() / 2.0, e.getZ(), Math.sqrt(e.squaredDistanceTo(c.player))};
        });
    }

    // ================= หาเส้นทาง (A*) =================
    static boolean danger(BlockState s) {
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.CACTUS)
            || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)
            || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE);
    }

    static boolean pass(ClientWorld w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty() && s.getFluidState().isEmpty() && !danger(s);
    }

    static boolean solidFloor(ClientWorld w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = w.getBlockState(p);
        return !s.getCollisionShape(w, p).isEmpty() && !s.isOf(Blocks.MAGMA_BLOCK);
    }

    static boolean walkable(ClientWorld w, int x, int y, int z) {
        return pass(w, x, y, z) && pass(w, x, y + 1, z) && solidFloor(w, x, y - 1, z);
    }

    static List<BlockPos> neighbors(ClientWorld w, BlockPos p) {
        List<BlockPos> out = new ArrayList<>(8);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int x = p.getX(), y = p.getY(), z = p.getZ();
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (walkable(w, nx, y, nz)) {
                out.add(new BlockPos(nx, y, nz));
                continue;
            }
            if (pass(w, x, y + 2, z) && walkable(w, nx, y + 1, nz)) {
                out.add(new BlockPos(nx, y + 1, nz));
                continue;
            }
            if (pass(w, nx, y, nz) && pass(w, nx, y + 1, nz)) {
                for (int k = 1; k <= 3; k++) {
                    if (walkable(w, nx, y - k, nz)) {
                        out.add(new BlockPos(nx, y - k, nz));
                        break;
                    }
                    if (!pass(w, nx, y - k, nz)) break;
                }
            }
        }
        return out;
    }

    static double heur(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) + Math.abs(a.getY() - b.getY());
    }

    static List<BlockPos> plan(BlockPos start, BlockPos goal, int maxNodes) {
        return onMain(() -> {
            ClientWorld w = mcc().world;
            if (w == null) return null;
            PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
            HashMap<Long, Node> seen = new HashMap<>();
            Node s0 = new Node(start, null, 0, heur(start, goal));
            open.add(s0);
            seen.put(start.asLong(), s0);
            Node best = s0;
            int count = 0;
            while (!open.isEmpty() && count < maxNodes) {
                Node cur = open.poll();
                if (cur.closed) continue;
                cur.closed = true;
                count++;
                if (cur.pos.getX() == goal.getX() && cur.pos.getZ() == goal.getZ()
                    && Math.abs(cur.pos.getY() - goal.getY()) <= 1) {
                    best = cur;
                    break;
                }
                if (heur(cur.pos, goal) < heur(best.pos, goal)) best = cur;
                for (BlockPos np : neighbors(w, cur.pos)) {
                    double g2 = cur.g + 1 + Math.abs(np.getY() - cur.pos.getY());
                    Node ex = seen.get(np.asLong());
                    if (ex != null && ex.g <= g2) continue;
                    Node nn = new Node(np, cur, g2, g2 + heur(np, goal));
                    seen.put(np.asLong(), nn);
                    open.add(nn);
                }
            }
            List<BlockPos> path = new ArrayList<>();
            for (Node n = best; n != null && n.parent != null; n = n.parent) path.add(n.pos);
            Collections.reverse(path);
            return path;
        });
    }

    static boolean reached(BlockPos p, BlockPos goal) {
        return p.getX() == goal.getX() && p.getZ() == goal.getZ() && Math.abs(p.getY() - goal.getY()) <= 1;
    }

    static void releaseMove() {
        want[IDX_W] = false;
        want[IDX_SPACE] = false;
    }

    static boolean follow(List<BlockPos> path) throws InterruptedException {
        for (BlockPos n : path) {
            long deadline = System.currentTimeMillis() + 5000;
            while (true) {
                double[] s = onMain(() -> {
                    var p = mcc().player;
                    return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
                });
                if (s == null) return false;
                double tx = n.getX() + 0.5, tz = n.getZ() + 0.5;
                double dx = tx - s[0], dz = tz - s[2];
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 0.3 && Math.abs(s[1] - n.getY()) < 1.2) break;
                final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                mcc().execute(() -> {
                    var p = mcc().player;
                    if (p != null) {
                        p.setYaw(yaw);
                        p.setPitch(15f);
                    }
                });
                want[IDX_W] = true;
                want[IDX_SPACE] = n.getY() > (int) Math.floor(s[1] + 0.001);
                Thread.sleep(50);
                if (System.currentTimeMillis() > deadline) return false;
            }
        }
        return true;
    }

    // ================= Python API =================
    public static class Api {
        // ---------- ปุ่ม ----------
        public void key(String k, int v) {
            chk();
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].equals(k)) want[i] = v != 0;
            }
        }

        public void sleep(double sec) throws InterruptedException {
            long end = System.currentTimeMillis() + (long) (sec * 1000);
            while (true) {
                chk();
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                Thread.sleep(Math.min(left, 50));
            }
        }

        public void click(String k) {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> tap(k.equals("use") ? c.options.useKey : c.options.attackKey));
        }

        public void slot(int n) {
            chk();
            if (n < 1 || n > 9) return;
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.hotbarKeys[n - 1]));
        }

        public void drop() {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.dropKey));
        }

        public void swap() {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.swapHandsKey));
        }

        public void inventory() {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> tap(c.options.inventoryKey));
        }

        public void closeScreen() {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> c.setScreen(null));
        }

        // ---------- กล้อง ----------
        public void turn(double yaw, double pitch) {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null) return;
                c.player.setYaw(c.player.getYaw() + (float) yaw);
                float p = c.player.getPitch() + (float) pitch;
                c.player.setPitch(Math.max(-90f, Math.min(90f, p)));
            });
        }

        public void setLook(double yaw, double pitch) {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null) return;
                c.player.setYaw((float) yaw);
                c.player.setPitch((float) Math.max(-90, Math.min(90, pitch)));
            });
        }

        public void lookAt(double x, double y, double z) {
            chk();
            mcc().execute(() -> faceTo(x, y, z));
        }

        // ---------- สถานะตัวเรา ----------
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

        public double level() {
            return num(() -> { var p = mcc().player; return p == null ? 0.0 : (double) p.experienceLevel; });
        }

        public double time() {
            return num(() -> { var w = mcc().world; return w == null ? 0.0 : (double) (w.getTimeOfDay() % 24000L); });
        }

        public boolean onGround() {
            return bool(() -> { var p = mcc().player; return p != null && p.isOnGround(); });
        }

        public boolean inWater() {
            return bool(() -> { var p = mcc().player; return p != null && p.isTouchingWater(); });
        }

        public boolean sneaking() {
            return bool(() -> { var p = mcc().player; return p != null && p.isSneaking(); });
        }

        public String dimension() {
            String r = onMain(() -> {
                var w = mcc().world;
                return w == null ? "none" : w.getRegistryKey().getValue().toString();
            });
            return r == null ? "none" : r;
        }

        // ตั้งเลือดขั้นต่ำที่สคริปต์จะหยุดเอง (0 = ปิด) ค่าเริ่มต้น 6
        public void safeHealth(double hp) {
            safeHp = hp;
        }

        // ---------- กระเป๋า ----------
        public String invItem(int i) {
            String r = onMain(() -> {
                var p = mcc().player;
                if (p == null) return "none";
                PlayerInventory inv = p.getInventory();
                if (i < 0 || i >= inv.size()) return "none";
                return idOf(inv.getStack(i));
            });
            return r == null ? "none" : r;
        }

        public int invAmount(int i) {
            Double v = onMain(() -> {
                var p = mcc().player;
                if (p == null) return 0.0;
                PlayerInventory inv = p.getInventory();
                if (i < 0 || i >= inv.size()) return 0.0;
                return (double) inv.getStack(i).getCount();
            });
            return v == null ? 0 : v.intValue();
        }

        public String hotbar(int n) {
            if (n < 1 || n > 9) return "none";
            return invItem(n - 1);
        }

        public int hotbarFind(String name) {
            String id = norm(name);
            for (int n = 1; n <= 9; n++) {
                if (invItem(n - 1).equals(id)) return n;
            }
            return 0;
        }

        public int invFind(String name) {
            String id = norm(name);
            for (int i = 0; i < 36; i++) {
                if (invItem(i).equals(id)) return i;
            }
            return -1;
        }

        public int count(String name) {
            String id = norm(name);
            int total = 0;
            for (int i = 0; i < 41; i++) {
                if (invItem(i).equals(id)) total += invAmount(i);
            }
            return total;
        }

        public String held() {
            String r = onMain(() -> {
                var p = mcc().player;
                return p == null ? "none" : idOf(p.getMainHandStack());
            });
            return r == null ? "none" : r;
        }

        public int heldDurability() {
            Double v = onMain(() -> {
                var p = mcc().player;
                if (p == null) return -1.0;
                ItemStack s = p.getMainHandStack();
                if (!s.isDamageable()) return -1.0;
                return (double) (s.getMaxDamage() - s.getDamage());
            });
            return v == null ? -1 : v.intValue();
        }

        // ---------- หน้าจอ GUI ----------
        public String screen() {
            String r = onMain(() -> {
                var s = mcc().currentScreen;
                return s == null ? "none" : s.getTitle().getString();
            });
            return r == null ? "none" : r;
        }

        public int screenSlots() {
            Double v = onMain(() -> {
                var p = mcc().player;
                return p == null ? 0.0 : (double) p.currentScreenHandler.slots.size();
            });
            return v == null ? 0 : v.intValue();
        }

        public String screenItem(int i) {
            String r = onMain(() -> {
                var p = mcc().player;
                if (p == null) return "none";
                var slots = p.currentScreenHandler.slots;
                if (i < 0 || i >= slots.size()) return "none";
                return idOf(slots.get(i).getStack());
            });
            return r == null ? "none" : r;
        }

        public int screenCount(int i) {
            Double v = onMain(() -> {
                var p = mcc().player;
                if (p == null) return 0.0;
                var slots = p.currentScreenHandler.slots;
                if (i < 0 || i >= slots.size()) return 0.0;
                return (double) slots.get(i).getStack().getCount();
            });
            return v == null ? 0 : v.intValue();
        }

        public int screenFind(String name) {
            String id = norm(name);
            int n = screenSlots();
            for (int i = 0; i < n; i++) {
                if (screenItem(i).equals(id)) return i;
            }
            return -1;
        }

        public void slotClick(int slot, int button, String mode) {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player == null || c.interactionManager == null) return;
                SlotActionType t = switch (mode) {
                    case "quick" -> SlotActionType.QUICK_MOVE;
                    case "throw" -> SlotActionType.THROW;
                    case "swap" -> SlotActionType.SWAP;
                    case "clone" -> SlotActionType.CLONE;
                    default -> SlotActionType.PICKUP;
                };
                c.interactionManager.clickSlot(c.player.currentScreenHandler.syncId, slot, button, t, c.player);
            });
        }

        // ---------- บล็อก ----------
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

        public double[] targetPos() {
            return onMain(() -> {
                HitResult h = mcc().crosshairTarget;
                if (h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK) {
                    BlockPos p = b.getBlockPos();
                    return new double[]{p.getX(), p.getY(), p.getZ()};
                }
                return null;
            });
        }

        public String block(int x, int y, int z) {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null) return "none";
                return Registries.BLOCK.getId(c.world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
            });
            return r == null ? "none" : r;
        }

        // ค่าคุณสมบัติของบล็อก เช่น prop(x,y,z,'age') -> "7" (ไม่มี = "")
        public String prop(int x, int y, int z, String name) {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null) return "";
                BlockState bs = c.world.getBlockState(new BlockPos(x, y, z));
                for (var e : bs.getEntries().entrySet()) {
                    if (e.getKey().getName().equals(name)) return String.valueOf(e.getValue());
                }
                return "";
            });
            return r == null ? "" : r;
        }

        // คุณสมบัติทั้งหมด เช่น "age=7"
        public String state(int x, int y, int z) {
            String r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null) return "";
                BlockState bs = c.world.getBlockState(new BlockPos(x, y, z));
                StringBuilder sb = new StringBuilder();
                for (var e : bs.getEntries().entrySet()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(e.getKey().getName()).append('=').append(e.getValue());
                }
                return sb.toString();
            });
            return r == null ? "" : r;
        }

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

        public double[] find(String name, int range) {
            final int rg = Math.min(range, 24);
            return onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null || c.player == null) return null;
                String id = norm(name);
                BlockPos p = c.player.getBlockPos();
                BlockPos.Mutable m = new BlockPos.Mutable();
                double bd = 1e18;
                int bx = 0, by = 0, bz = 0;
                boolean found = false;
                for (int dx = -rg; dx <= rg; dx++) {
                    for (int dy = -rg; dy <= rg; dy++) {
                        for (int dz = -rg; dz <= rg; dz++) {
                            m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                            String bid = Registries.BLOCK.getId(c.world.getBlockState(m).getBlock()).toString();
                            if (bid.equals(id)) {
                                double d = dx * dx + dy * dy + dz * dz;
                                if (d < bd) {
                                    bd = d;
                                    bx = m.getX();
                                    by = m.getY();
                                    bz = m.getZ();
                                    found = true;
                                }
                            }
                        }
                    }
                }
                if (!found) return null;
                return new double[]{bx + 0.5, by + 0.5, bz + 0.5, Math.sqrt(bd)};
            });
        }

        // ---------- เดิน / ขุด / วาง / ตี ----------
        // เดินไปพิกัดโดยหาทางเอง ได้ True ถ้าถึง
        public boolean walkTo(double x, double y, double z) throws InterruptedException {
            BlockPos goal = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            for (int attempt = 0; attempt < 5; attempt++) {
                BlockPos start = onMain(() -> {
                    var p = mcc().player;
                    return p == null ? null : p.getBlockPos();
                });
                if (start == null) return false;
                if (reached(start, goal)) {
                    releaseMove();
                    return true;
                }
                List<BlockPos> path = plan(start, goal, 6000);
                if (path == null || path.isEmpty()) {
                    releaseMove();
                    return false;
                }
                follow(path);
                releaseMove();
            }
            BlockPos now = onMain(() -> {
                var p = mcc().player;
                return p == null ? null : p.getBlockPos();
            });
            return now != null && reached(now, goal);
        }

        // ขุดบล็อกที่พิกัดนี้ (เลือกเครื่องมือเอง) ได้ True ถ้าบล็อกหาย
        public boolean mine(int x, int y, int z) throws InterruptedException {
            final BlockPos pos = new BlockPos(x, y, z);
            Integer tool = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null || c.player == null) return -2;
                BlockState st = c.world.getBlockState(pos);
                if (st.isAir()) return -3;
                double ex = pos.getX() + 0.5 - c.player.getX();
                double ey = pos.getY() + 0.5 - c.player.getEyeY();
                double ez = pos.getZ() + 0.5 - c.player.getZ();
                if (ex * ex + ey * ey + ez * ez > 4.5 * 4.5) return -2;
                int best = -1;
                float bs = 1.0f;
                for (int i = 0; i < 9; i++) {
                    float sp = c.player.getInventory().getStack(i).getMiningSpeedMultiplier(st);
                    if (sp > bs) {
                        bs = sp;
                        best = i;
                    }
                }
                return best;
            });
            if (tool == null || tool == -2) return false;
            if (tool == -3) return true;
            if (tool >= 0) {
                final int ti = tool;
                mcc().execute(() -> tap(mcc().options.hotbarKeys[ti]));
                sleep(0.15);
            }
            long end = System.currentTimeMillis() + 15000;
            try {
                want[IDX_ATTACK] = true;
                while (System.currentTimeMillis() < end) {
                    Boolean gone = onMain(() -> {
                        MinecraftClient c = mcc();
                        if (c.world == null) return null;
                        if (c.world.getBlockState(pos).isAir()) return true;
                        faceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        return false;
                    });
                    if (gone == null) return false;
                    if (gone) return true;
                    Thread.sleep(50);
                }
                return false;
            } finally {
                want[IDX_ATTACK] = false;
            }
        }

        // วางบล็อกที่ถืออยู่ลงพิกัดนี้ (ต้องมีบล็อกแข็งติดอยู่ข้าง ๆ) ได้ True ถ้าวางสำเร็จ
        public boolean place(int x, int y, int z) {
            Boolean r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.player == null || c.world == null || c.interactionManager == null) return false;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState ps = c.world.getBlockState(pos);
                if (!ps.getCollisionShape(c.world, pos).isEmpty()) return false;
                double ex = pos.getX() + 0.5 - c.player.getX();
                double ey = pos.getY() + 0.5 - c.player.getEyeY();
                double ez = pos.getZ() + 0.5 - c.player.getZ();
                if (ex * ex + ey * ey + ez * ez > 4.5 * 4.5) return false;
                for (Direction d : Direction.values()) {
                    BlockPos n = pos.offset(d);
                    BlockState ns = c.world.getBlockState(n);
                    if (ns.getCollisionShape(c.world, n).isEmpty()) continue;
                    Direction face = d.getOpposite();
                    Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                    BlockHitResult bhr = new BlockHitResult(hit, face, n, false);
                    ActionResult res = c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, bhr);
                    if (res.isAccepted()) {
                        c.player.swingHand(Hand.MAIN_HAND);
                        return true;
                    }
                }
                return false;
            });
            return r != null && r;
        }

        // ตีมอนใกล้สุดตรง ๆ 1 ครั้ง (type = ส่วนของชื่อ หรือ '' = ทุกชนิด) True ถ้าตีโดน
        // คืน False ถ้าไม่มีมอนในระยะ หรือยังชาร์จดาเมจไม่เต็ม (เรียกซ้ำเรื่อย ๆ ได้)
        public boolean attackNearest(String type, double reach) {
            Boolean r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.player == null || c.interactionManager == null) return false;
                LivingEntity e = nearestEntity(type == null || type.isEmpty() ? null : type, reach);
                if (e == null) return false;
                if (c.player.getAttackCooldownProgress(0.5f) < 0.9f) return false;
                faceTo(e.getX(), e.getY() + e.getHeight() / 2.0, e.getZ());
                c.interactionManager.attackEntity(c.player, e);
                c.player.swingHand(Hand.MAIN_HAND);
                return true;
            });
            return r != null && r;
        }

        // ---------- มอน / ผู้เล่น ----------
        public double[] nearest(double range) {
            return findEntity(null, range);
        }

        public double[] nearestOf(String type, double range) {
            return findEntity(type, range);
        }

        public Object[][] entities(double range) {
            Object[][] r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.player == null || c.world == null) return new Object[0][];
                List<LivingEntity> list = c.world.getEntitiesByClass(LivingEntity.class,
                    c.player.getBoundingBox().expand(range), e -> e != c.player && e.isAlive());
                List<Object[]> out = new ArrayList<>();
                for (LivingEntity e : list) {
                    out.add(new Object[]{
                        Registries.ENTITY_TYPE.getId(e.getType()).toString(),
                        e.getX(), e.getY(), e.getZ(),
                        Math.sqrt(e.squaredDistanceTo(c.player)),
                        (double) e.getHealth()});
                }
                return out.toArray(new Object[0][]);
            });
            return r == null ? new Object[0][] : r;
        }

        public String[] players() {
            String[] r = onMain(() -> {
                MinecraftClient c = mcc();
                if (c.world == null) return new String[0];
                List<String> out = new ArrayList<>();
                for (var p : c.world.getPlayers()) {
                    if (p != c.player) out.add(p.getName().getString());
                }
                return out.toArray(new String[0]);
            });
            return r == null ? new String[0] : r;
        }

        // ---------- แชท / คำสั่ง ----------
        public void say(String s) {
            msg(s);
        }

        public void chat(String s) {
            chk();
            MinecraftClient c = mcc();
            c.execute(() -> {
                if (c.player != null) c.player.networkHandler.sendChatMessage(s);
            });
        }

        public void command(String s) {
            chk();
            MinecraftClient c = mcc();
            String cmd = s.startsWith("/") ? s.substring(1) : s;
            c.execute(() -> {
                if (c.player != null) c.player.networkHandler.sendChatCommand(cmd);
            });
        }

        public String[] chatLog(int n) {
            synchronized (chatBuf) {
                int from = Math.max(0, chatBuf.size() - n);
                return chatBuf.subList(from, chatBuf.size()).toArray(new String[0]);
            }
        }

        public void chatClear() {
            chatBuf.clear();
        }
    }

    // ================= ควบคุมสคริปต์ =================
    static void clearKeys() {
        for (int i = 0; i < NAMES.length; i++) want[i] = false;
    }

    static boolean active() {
        Thread t = running;
        return t != null && t.isAlive();
    }

    static void stop() {
        runId++;
        Thread t = running;
        if (t != null) t.interrupt();
        clearKeys();
    }

    static void stopWith(String why) {
        stop();
        msg(why);
    }

    static String describe(Throwable e) {
        String full = String.valueOf(e);
        String[] lines = full.split("\n");
        String last = full.trim();
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                last = lines[i].trim();
                break;
            }
        }
        String where = "";
        Matcher m = FILE_LINE.matcher(full);
        while (m.find()) {
            String file = m.group(1);
            if (file.endsWith(".py")) {
                where = file.substring(file.lastIndexOf('/') + 1) + " บรรทัด " + m.group(2) + ": ";
            }
        }
        if (last.length() > 160) last = last.substring(0, 160);
        return where + last;
    }

    static void run(String name, String[] argv) {
        stop();
        Path f = dir.resolve(name + ".py");
        if (!Files.exists(f)) {
            msg("[py] ไม่พบไฟล์ " + f.getFileName());
            return;
        }
        final int myId = runId;
        safeHp = 6;
        Thread t = new Thread(() -> {
            myRun.set(myId);
            try {
                System.setProperty("python.import.site", "false");
                System.setProperty("python.cachedir.skip", "true");
                PythonInterpreter py = new PythonInterpreter();
                py.set("mc", new Api());
                py.set("_a", argv);
                String init = """
                    import sys, __builtin__
                    __builtin__.mc = mc
                    __builtin__.args = [x for x in _a]
                    D = r'@@'
                    for k in list(sys.modules.keys()):
                        m = sys.modules.get(k)
                        f = getattr(m, '__file__', None)
                        if f and str(f).startswith(D):
                            del sys.modules[k]
                    if D not in sys.path:
                        sys.path.insert(0, D)
                    """.replace("@@", dir.toString());
                py.exec(init);
                py.execfile(f.toString());
                if (runId == myId) msg("[py] " + name + " จบแล้ว");
            } catch (Throwable e) {
                String m = String.valueOf(e);
                if (m.contains("ScriptStop") || m.contains("InterruptedException")) {
                    if (runId == myId) msg("[py] หยุดแล้ว");
                } else {
                    e.printStackTrace();
                    msg("[py] error " + describe(e));
                }
            } finally {
                if (runId == myId) clearKeys();
            }
        }, "py-script");
        t.setDaemon(true);
        running = t;
        t.start();
    }

    static void check(String name) {
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
                String p = f.toString();
                py.exec("compile(open(r'" + p + "', 'rb').read(), r'" + p + "', 'exec')");
                msg("[py] " + name + ".py ไม่พบ syntax error");
            } catch (Throwable e) {
                msg("[py] " + name + ": " + describe(e));
            }
        }, "py-check");
        t.setDaemon(true);
        t.start();
    }

    static String[] splitArgs(String s) {
        String a = s.trim();
        return a.isEmpty() ? new String[0] : a.split("\\s+");
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

        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.pyctl.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.pyctl"));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) addChat(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
            addChat(message.getString()));

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
                .then(ClientCommandManager.literal("check")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            check(StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        run(StringArgumentType.getString(ctx, "name"), new String[0]);
                        return 1;
                    })
                    .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            run(StringArgumentType.getString(ctx, "name"),
                                splitArgs(StringArgumentType.getString(ctx, "args")));
                            return 1;
                        }))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (stopKey.wasPressed()) {
                boolean a = active();
                stop();
                if (a) msg("[py] หยุดด้วยปุ่ม");
            }
            if (active()) {
                if (client.player == null || client.world == null || !client.player.isAlive()) {
                    stopWith("[py] หยุด: ตายหรือออกจากโลก");
                } else if (safeHp > 0 && client.player.getHealth() <= safeHp) {
                    stopWith("[py] หยุด: เลือดต่ำ");
                }
            }
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
