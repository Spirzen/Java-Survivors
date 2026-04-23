package com.diabloid;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class DiabloidGame {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Русские Выживанцы");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.setContentPane(new GamePanel());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static final class GamePanel extends JPanel implements Runnable {
        private static final int WIDTH = 1280;
        private static final int HEIGHT = 720;
        private static final double CONTACT_DAMAGE_TICK = 0.25;

        private final Random rng = new Random();
        private final Thread gameThread;

        private boolean up;
        private boolean down;
        private boolean left;
        private boolean right;

        private boolean running = true;
        private GameState gameState = GameState.MENU;
        private UpgradeState upgradeState = UpgradeState.NONE;

        private final Player player = new Player();
        private final List<Enemy> enemies = new ArrayList<>();
        private final List<Projectile> projectiles = new ArrayList<>();
        private final List<XpOrb> xpOrbs = new ArrayList<>();
        private final List<String> upgradeChoices = new ArrayList<>();
        private final Set<WeaponType> unlockedWeapons = new HashSet<>();

        private double worldTime;
        private double spawnTimer;
        private double contactDamageTimer;
        private int score;
        private int pendingLevelUps;

        private final String[] statUpgrades = {
            "Сила +20%", "Скорость атаки +20%", "Скорость движения +15%",
            "Макс. HP +20", "Регенерация +0.5", "Магнит +20%",
            "Броня +8%", "Скорость снаряда +20%", "Размер снаряда +20%",
            "Урон +5"
        };

        private GamePanel() {
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setFocusable(true);
            setBackground(new Color(17, 17, 20));
            addKeyListener(new KeyHandler());

            gameThread = new Thread(this, "game-loop");
            gameThread.start();
        }

        @Override
        public void run() {
            long previous = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                double dt = (now - previous) / 1_000_000_000.0;
                previous = now;
                dt = Math.min(dt, 0.033);

                update(dt);
                repaint();

                try {
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        private void update(double dt) {
            if (gameState == GameState.MENU || gameState == GameState.GAME_OVER || upgradeState == UpgradeState.PAUSED_FOR_UPGRADE) {
                return;
            }

            worldTime += dt;
            player.heal(dt);
            player.updateMovement(dt, up, down, left, right, WIDTH, HEIGHT);
            updateWeapons(dt);
            spawnEnemies(dt);
            updateEnemies(dt);
            updateProjectiles(dt);
            updateXpOrbs(dt);
        }

        private void updateWeapons(double dt) {
            if (enemies.isEmpty()) {
                return;
            }
            if (unlockedWeapons.contains(WeaponType.MAGIC_BOLT)) {
                player.shotCooldown -= dt;
                if (player.shotCooldown <= 0) {
                    shootMagicBolt();
                    player.shotCooldown = Math.max(0.08, 0.45 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.TRIPLE_CAST)) {
                player.tripleCooldown -= dt;
                if (player.tripleCooldown <= 0) {
                    shootTripleCast();
                    player.tripleCooldown = Math.max(0.20, 1.10 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.PULSE_RING)) {
                player.pulseCooldown -= dt;
                if (player.pulseCooldown <= 0) {
                    shootPulseRing();
                    player.pulseCooldown = Math.max(0.50, 2.20 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.PIERCE_LANCE)) {
                player.lanceCooldown -= dt;
                if (player.lanceCooldown <= 0) {
                    shootPierceLance();
                    player.lanceCooldown = Math.max(0.16, 0.95 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.DAMAGE_AURA)) {
                updateDamageAura(dt);
            }
            if (unlockedWeapons.contains(WeaponType.CHAIN_LIGHTNING)) {
                player.lightningCooldown -= dt;
                if (player.lightningCooldown <= 0) {
                    castChainLightning();
                    player.lightningCooldown = Math.max(0.25, 1.70 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.SAW_BLADE)) {
                player.sawCooldown -= dt;
                if (player.sawCooldown <= 0) {
                    shootSawBlade();
                    player.sawCooldown = Math.max(0.20, 1.35 / player.attackSpeedMultiplier);
                }
            }
        }

        private void shootMagicBolt() {
            Enemy target = findNearestEnemy();
            if (target == null) return;
            spawnProjectileTowards(target, 18.0, 6.0, 600.0, 0);
        }

        private void shootTripleCast() {
            Enemy target = findNearestEnemy();
            if (target == null) return;
            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double baseAngle = Math.atan2(dy, dx);
            double[] spread = {-0.22, 0, 0.22};
            for (double offset : spread) {
                double angle = baseAngle + offset;
                double speed = 540.0 * player.projectileSpeedMultiplier;
                double vx = Math.cos(angle) * speed;
                double vy = Math.sin(angle) * speed;
                double radius = 5.5 * player.projectileSizeMultiplier;
                double damage = (12.0 * player.damageMultiplier) + player.flatDamageBonus;
                projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 0));
            }
        }

        private void shootPulseRing() {
            int count = 10;
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2.0 / count) * i;
                double speed = 430.0 * player.projectileSpeedMultiplier;
                double vx = Math.cos(angle) * speed;
                double vy = Math.sin(angle) * speed;
                double radius = 5.0 * player.projectileSizeMultiplier;
                double damage = (14.0 * player.damageMultiplier) + player.flatDamageBonus;
                projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 0));
            }
        }

        private void shootPierceLance() {
            Enemy target = findNearestEnemy();
            if (target == null) return;
            spawnProjectileTowards(target, 24.0, 7.0, 760.0, 2);
        }

        private void updateDamageAura(double dt) {
            player.auraTickCooldown -= dt;
            if (player.auraTickCooldown > 0) {
                return;
            }
            player.auraTickCooldown = 0.28;
            double auraRadius = player.auraRadius * player.projectileSizeMultiplier;
            double auraDamage = (7.5 * player.damageMultiplier) + player.flatDamageBonus * 0.6;
            for (Enemy enemy : new ArrayList<>(enemies)) {
                if (distanceSq(player.x, player.y, enemy.x, enemy.y) <= auraRadius * auraRadius) {
                    damageEnemy(enemy, auraDamage);
                }
            }
        }

        private void castChainLightning() {
            Enemy first = findNearestEnemy();
            if (first == null) return;
            Enemy second = null;
            Enemy third = null;
            double chainRangeSq = 210 * 210;
            for (Enemy enemy : enemies) {
                if (enemy == first) continue;
                if (distanceSq(first.x, first.y, enemy.x, enemy.y) <= chainRangeSq) {
                    second = enemy;
                    break;
                }
            }
            if (second != null) {
                for (Enemy enemy : enemies) {
                    if (enemy == first || enemy == second) continue;
                    if (distanceSq(second.x, second.y, enemy.x, enemy.y) <= chainRangeSq) {
                        third = enemy;
                        break;
                    }
                }
            }
            double baseDamage = (26.0 * player.damageMultiplier) + player.flatDamageBonus;
            damageEnemy(first, baseDamage);
            if (second != null) damageEnemy(second, baseDamage * 0.72);
            if (third != null) damageEnemy(third, baseDamage * 0.5);
        }

        private void shootSawBlade() {
            Enemy target = findNearestEnemy();
            if (target == null) return;
            spawnProjectileTowards(target, 20.0, 8.0, 500.0, 5);
        }

        private Enemy findNearestEnemy() {
            return enemies.stream()
                .min(Comparator.comparingDouble(e -> distanceSq(player.x, player.y, e.x, e.y)))
                .orElse(null);
        }

        private void spawnProjectileTowards(Enemy target, double baseDamage, double baseRadius, double baseSpeed, int pierce) {
            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double len = Math.hypot(dx, dy);
            if (len < 0.001) return;

            double speed = baseSpeed * player.projectileSpeedMultiplier;
            double radius = baseRadius * player.projectileSizeMultiplier;
            double damage = (baseDamage * player.damageMultiplier) + player.flatDamageBonus;
            projectiles.add(new Projectile(player.x, player.y, (dx / len) * speed, (dy / len) * speed, damage, radius, pierce));
        }

        private void spawnEnemies(double dt) {
            spawnTimer -= dt;
            if (spawnTimer > 0) {
                return;
            }

            double t = worldTime;
            double difficulty = 1.0 + (t * 0.018) + (Math.pow(t, 1.18) * 0.0012);
            int batch = 1 + (int) Math.floor(t / 45.0) + (rng.nextDouble() < Math.min(0.55, 0.20 + t / 260.0) ? 1 : 0);
            spawnTimer = Math.max(0.08, 0.78 - (t * 0.0038));

            for (int i = 0; i < batch; i++) {
                enemies.add(spawnEnemy(difficulty));
            }
        }

        private Enemy spawnEnemy(double difficulty) {
            boolean tank = rng.nextDouble() < 0.20 + Math.min(0.25, worldTime / 240.0);
            double side = rng.nextDouble();
            double x;
            double y;
            if (side < 0.25) {
                x = -50;
                y = rng.nextDouble() * HEIGHT;
            } else if (side < 0.5) {
                x = WIDTH + 50;
                y = rng.nextDouble() * HEIGHT;
            } else if (side < 0.75) {
                x = rng.nextDouble() * WIDTH;
                y = -50;
            } else {
                x = rng.nextDouble() * WIDTH;
                y = HEIGHT + 50;
            }

            if (tank) {
                return new Enemy(x, y, 22, 95 * difficulty, 62 + difficulty * 5, 10 + difficulty * 2, 4 + (int) difficulty);
            }
            return new Enemy(x, y, 14, 40 * difficulty, 105 + difficulty * 8, 6 + difficulty, 2 + (int) (difficulty * 0.8));
        }

        private void updateEnemies(double dt) {
            for (Enemy e : enemies) {
                double dx = player.x - e.x;
                double dy = player.y - e.y;
                double len = Math.hypot(dx, dy);
                if (len > 0.0001) {
                    e.x += (dx / len) * e.speed * dt;
                    e.y += (dy / len) * e.speed * dt;
                }
            }

            contactDamageTimer -= dt;
            if (contactDamageTimer <= 0) {
                double sumDamage = 0;
                for (Enemy e : enemies) {
                    if (distanceSq(e.x, e.y, player.x, player.y) <= (e.radius + player.radius) * (e.radius + player.radius)) {
                        sumDamage += e.contactDamage;
                    }
                }
                if (sumDamage > 0) {
                    player.hp -= sumDamage * (1.0 - player.armorReduction);
                    if (player.hp <= 0) {
                        player.hp = 0;
                        gameState = GameState.GAME_OVER;
                    }
                }
                contactDamageTimer = CONTACT_DAMAGE_TICK;
            }
        }

        private void updateProjectiles(double dt) {
            Iterator<Projectile> pi = projectiles.iterator();
            while (pi.hasNext()) {
                Projectile p = pi.next();
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.life -= dt;

                if (p.life <= 0 || p.x < -100 || p.y < -100 || p.x > WIDTH + 100 || p.y > HEIGHT + 100) {
                    pi.remove();
                    continue;
                }

                Enemy hit = null;
                for (Enemy e : enemies) {
                    if (distanceSq(p.x, p.y, e.x, e.y) <= (p.radius + e.radius) * (p.radius + e.radius)) {
                        hit = e;
                        break;
                    }
                }

                if (hit != null) {
                    damageEnemy(hit, p.damage);
                    if (p.pierce > 0) {
                        p.pierce--;
                    } else {
                        pi.remove();
                    }
                }
            }
        }

        private void damageEnemy(Enemy enemy, double damage) {
            enemy.hp -= damage;
            if (enemy.hp <= 0) {
                enemies.remove(enemy);
                score += enemy.score;
                xpOrbs.add(new XpOrb(enemy.x, enemy.y, enemy.xpValue));
            }
        }

        private void updateXpOrbs(double dt) {
            Iterator<XpOrb> oi = xpOrbs.iterator();
            while (oi.hasNext()) {
                XpOrb orb = oi.next();
                double dx = player.x - orb.x;
                double dy = player.y - orb.y;
                double dist = Math.hypot(dx, dy);

                if (dist < player.magnetRadius) {
                    double speed = 160 + (player.magnetRadius - Math.min(player.magnetRadius, dist)) * 2.3;
                    if (dist > 0.001) {
                        orb.x += (dx / dist) * speed * dt;
                        orb.y += (dy / dist) * speed * dt;
                    }
                }

                if (distanceSq(orb.x, orb.y, player.x, player.y) < (player.radius + 7) * (player.radius + 7)) {
                    oi.remove();
                    player.xp += orb.value;
                    while (player.xp >= player.xpToNext) {
                        player.xp -= player.xpToNext;
                        player.level++;
                        player.xpToNext = (int) Math.round(player.xpToNext * 1.26 + 8);
                        pendingLevelUps++;
                    }
                    if (pendingLevelUps > 0 && upgradeState == UpgradeState.NONE) {
                        rollUpgradeChoices();
                        upgradeState = UpgradeState.PAUSED_FOR_UPGRADE;
                    }
                }
            }
        }

        private void rollUpgradeChoices() {
            upgradeChoices.clear();
            List<String> pool = new ArrayList<>();
            for (String u : statUpgrades) {
                pool.add(u);
            }
            if (!unlockedWeapons.contains(WeaponType.TRIPLE_CAST)) {
                pool.add("Оружие: Тройной залп");
            }
            if (!unlockedWeapons.contains(WeaponType.PULSE_RING)) {
                pool.add("Оружие: Кольцо импульса");
            }
            if (!unlockedWeapons.contains(WeaponType.PIERCE_LANCE)) {
                pool.add("Оружие: Пронзающее копье");
            }
            if (!unlockedWeapons.contains(WeaponType.DAMAGE_AURA)) {
                pool.add("Оружие: Аура боли");
            }
            if (!unlockedWeapons.contains(WeaponType.CHAIN_LIGHTNING)) {
                pool.add("Оружие: Цепная молния");
            }
            if (!unlockedWeapons.contains(WeaponType.SAW_BLADE)) {
                pool.add("Оружие: Пила");
            }
            for (int i = 0; i < 4 && !pool.isEmpty(); i++) {
                int idx = rng.nextInt(pool.size());
                upgradeChoices.add(pool.remove(idx));
            }
        }

        private void applyUpgrade(int idx) {
            if (idx < 0 || idx >= upgradeChoices.size()) {
                return;
            }
            String picked = upgradeChoices.get(idx);
            if (picked.startsWith("Сила")) {
                player.damageMultiplier *= 1.20;
            } else if (picked.startsWith("Скорость атаки")) {
                player.attackSpeedMultiplier *= 1.20;
            } else if (picked.startsWith("Скорость движения")) {
                player.moveSpeed *= 1.15;
            } else if (picked.startsWith("Макс. HP")) {
                player.maxHp += 20;
                player.hp = Math.min(player.maxHp, player.hp + 20);
            } else if (picked.startsWith("Регенерация")) {
                player.regen += 0.5;
            } else if (picked.startsWith("Магнит")) {
                player.magnetRadius *= 1.20;
            } else if (picked.startsWith("Броня")) {
                player.armorReduction = Math.min(0.70, player.armorReduction + 0.08);
            } else if (picked.startsWith("Скорость снаряда")) {
                player.projectileSpeedMultiplier *= 1.20;
            } else if (picked.startsWith("Размер снаряда")) {
                player.projectileSizeMultiplier *= 1.20;
            } else if (picked.startsWith("Урон +5")) {
                player.flatDamageBonus += 5.0;
            } else if (picked.contains("Тройной залп")) {
                unlockedWeapons.add(WeaponType.TRIPLE_CAST);
            } else if (picked.contains("Кольцо импульса")) {
                unlockedWeapons.add(WeaponType.PULSE_RING);
            } else if (picked.contains("Пронзающее копье")) {
                unlockedWeapons.add(WeaponType.PIERCE_LANCE);
            } else if (picked.contains("Аура боли")) {
                unlockedWeapons.add(WeaponType.DAMAGE_AURA);
            } else if (picked.contains("Цепная молния")) {
                unlockedWeapons.add(WeaponType.CHAIN_LIGHTNING);
            } else if (picked.contains("Пила")) {
                unlockedWeapons.add(WeaponType.SAW_BLADE);
            }

            pendingLevelUps--;
            if (pendingLevelUps > 0) {
                rollUpgradeChoices();
            } else {
                upgradeState = UpgradeState.NONE;
                upgradeChoices.clear();
            }
        }

        private void resetRun() {
            enemies.clear();
            projectiles.clear();
            xpOrbs.clear();
            upgradeChoices.clear();
            unlockedWeapons.clear();
            unlockedWeapons.add(WeaponType.MAGIC_BOLT);

            player.reset();
            worldTime = 0;
            spawnTimer = 0.4;
            contactDamageTimer = 0;
            score = 0;
            pendingLevelUps = 0;
            gameState = GameState.PLAYING;
            upgradeState = UpgradeState.NONE;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawBackground(g2);
            drawGame(g2);
            drawHud(g2);

            if (gameState == GameState.MENU) {
                drawMenu(g2);
            } else if (gameState == GameState.GAME_OVER) {
                drawGameOver(g2);
            } else if (upgradeState == UpgradeState.PAUSED_FOR_UPGRADE) {
                drawUpgradeOverlay(g2);
            }
        }

        private void drawBackground(Graphics2D g2) {
            g2.setColor(new Color(27, 27, 31));
            for (int x = 0; x < WIDTH; x += 64) {
                for (int y = 0; y < HEIGHT; y += 64) {
                    int pulse = (int) ((Math.sin((x + y + worldTime * 45) * 0.03) + 1) * 8);
                    g2.setColor(new Color(26 + pulse, 26 + pulse, 32 + pulse));
                    g2.fillRect(x, y, 62, 62);
                }
            }
        }

        private void drawGame(Graphics2D g2) {
            for (XpOrb orb : xpOrbs) {
                g2.setColor(new Color(80, 180, 255));
                g2.fill(new Ellipse2D.Double(orb.x - 4, orb.y - 4, 8, 8));
            }

            for (Projectile p : projectiles) {
                g2.setColor(new Color(255, 228, 120));
                g2.fill(new Ellipse2D.Double(p.x - p.radius, p.y - p.radius, p.radius * 2, p.radius * 2));
            }

            for (Enemy e : enemies) {
                g2.setColor(e.radius > 18 ? new Color(155, 52, 52) : new Color(190, 75, 75));
                g2.fill(new Ellipse2D.Double(e.x - e.radius, e.y - e.radius, e.radius * 2, e.radius * 2));
            }

            if (unlockedWeapons.contains(WeaponType.DAMAGE_AURA)) {
                double aura = player.auraRadius * player.projectileSizeMultiplier;
                g2.setColor(new Color(150, 90, 255, 65));
                g2.fill(new Ellipse2D.Double(player.x - aura, player.y - aura, aura * 2, aura * 2));
            }

            g2.setColor(new Color(80, 230, 120));
            g2.fill(new Ellipse2D.Double(player.x - player.radius, player.y - player.radius, player.radius * 2, player.radius * 2));
        }

        private void drawHud(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(16, 16, 360, 116, 16, 16);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.BOLD, 17));
            g2.drawString("Уровень: " + player.level, 30, 45);
            g2.drawString("Время: " + formatTime(worldTime), 30, 71);
            g2.drawString("Счет: " + score, 30, 97);

            int hpBarWidth = 220;
            g2.setColor(new Color(75, 25, 25));
            g2.fillRoundRect(145, 53, hpBarWidth, 16, 8, 8);
            int hpFill = (int) (hpBarWidth * (player.hp / player.maxHp));
            g2.setColor(new Color(90, 210, 90));
            g2.fillRoundRect(145, 53, hpFill, 16, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString("Здоровье " + (int) player.hp + "/" + (int) player.maxHp, 145, 47);

            int xpBarWidth = 220;
            g2.setColor(new Color(25, 25, 65));
            g2.fillRoundRect(145, 90, xpBarWidth, 12, 8, 8);
            int xpFill = (int) (xpBarWidth * (player.xp / (double) player.xpToNext));
            g2.setColor(new Color(120, 160, 255));
            g2.fillRoundRect(145, 90, xpFill, 12, 8, 8);

            drawWeaponIcons(g2);
        }

        private void drawWeaponIcons(Graphics2D g2) {
            int x = 395;
            int y = 22;
            int size = 32;
            for (WeaponType weapon : unlockedWeapons) {
                g2.setColor(new Color(30, 30, 35, 210));
                g2.fillRoundRect(x, y, size, size, 8, 8);
                g2.setColor(getWeaponColor(weapon));
                g2.fillRoundRect(x + 4, y + 4, size - 8, size - 8, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Dialog", Font.BOLD, 12));
                g2.drawString(getWeaponShortName(weapon), x + 7, y + 21);
                x += 38;
            }
        }

        private Color getWeaponColor(WeaponType weapon) {
            switch (weapon) {
                case MAGIC_BOLT: return new Color(100, 200, 255);
                case TRIPLE_CAST: return new Color(140, 230, 120);
                case PULSE_RING: return new Color(255, 200, 100);
                case PIERCE_LANCE: return new Color(255, 120, 120);
                case DAMAGE_AURA: return new Color(180, 120, 255);
                case CHAIN_LIGHTNING: return new Color(200, 220, 255);
                case SAW_BLADE: return new Color(210, 210, 210);
                default: return new Color(180, 180, 180);
            }
        }

        private String getWeaponShortName(WeaponType weapon) {
            switch (weapon) {
                case MAGIC_BOLT: return "МБ";
                case TRIPLE_CAST: return "ТЗ";
                case PULSE_RING: return "КИ";
                case PIERCE_LANCE: return "ПК";
                case DAMAGE_AURA: return "АУ";
                case CHAIN_LIGHTNING: return "ЦМ";
                case SAW_BLADE: return "ПЛ";
                default: return "??";
            }
        }

        private void drawMenu(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, 0, WIDTH, HEIGHT);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.BOLD, 58));
            g2.drawString("РУССКИЕ ВЫЖИВАНЦЫ", 300, 220);

            g2.setFont(new Font("Dialog", Font.PLAIN, 24));
            g2.drawString("WASD/СТРЕЛКИ - движение", 450, 320);
            g2.drawString("Атака по ближайшему врагу автоматическая", 390, 355);
            g2.drawString("ENTER - начать игру", 500, 420);
        }

        private void drawGameOver(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, 0, WIDTH, HEIGHT);

            g2.setColor(new Color(255, 120, 120));
            g2.setFont(new Font("Dialog", Font.BOLD, 62));
            g2.drawString("ВЫ ПОГИБЛИ", 430, 260);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.PLAIN, 30));
            g2.drawString("Время выживания: " + formatTime(worldTime), 430, 340);
            g2.drawString("Счет: " + score, 560, 380);
            g2.drawString("ENTER - начать заново", 470, 460);
        }

        private void drawUpgradeOverlay(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 190));
            g2.fillRect(0, 0, WIDTH, HEIGHT);

            g2.setColor(new Color(230, 230, 255));
            g2.setFont(new Font("Dialog", Font.BOLD, 42));
            g2.drawString("Выберите улучшение", 410, 170);

            g2.setFont(new Font("Dialog", Font.BOLD, 28));
            for (int i = 0; i < upgradeChoices.size(); i++) {
                int y = 230 + i * 105;
                g2.setColor(new Color(55, 55, 95));
                g2.fillRoundRect(330, y - 42, 620, 84, 16, 16);
                g2.setColor(Color.WHITE);
                g2.drawString((i + 1) + ". " + upgradeChoices.get(i), 370, y + 10);
            }
        }

        private static String formatTime(double seconds) {
            int s = (int) seconds;
            int min = s / 60;
            int sec = s % 60;
            return String.format("%02d:%02d", min, sec);
        }

        private static double distanceSq(double x1, double y1, double x2, double y2) {
            double dx = x1 - x2;
            double dy = y1 - y2;
            return dx * dx + dy * dy;
        }

        private final class KeyHandler extends KeyAdapter {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) up = true;
                if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) down = true;
                if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) left = true;
                if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) right = true;

                if (code == KeyEvent.VK_ENTER) {
                    if (gameState == GameState.MENU || gameState == GameState.GAME_OVER) {
                        resetRun();
                    }
                }

                if (upgradeState == UpgradeState.PAUSED_FOR_UPGRADE) {
                    if (code == KeyEvent.VK_1) applyUpgrade(0);
                    if (code == KeyEvent.VK_2) applyUpgrade(1);
                    if (code == KeyEvent.VK_3) applyUpgrade(2);
                    if (code == KeyEvent.VK_4) applyUpgrade(3);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) up = false;
                if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) down = false;
                if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) left = false;
                if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) right = false;
            }
        }
    }

    private enum GameState {
        MENU, PLAYING, GAME_OVER
    }

    private enum UpgradeState {
        NONE, PAUSED_FOR_UPGRADE
    }

    private enum WeaponType {
        MAGIC_BOLT, TRIPLE_CAST, PULSE_RING, PIERCE_LANCE, DAMAGE_AURA, CHAIN_LIGHTNING, SAW_BLADE
    }

    private static final class Player {
        private double x = 640;
        private double y = 360;
        private double radius = 16;

        private double maxHp = 100;
        private double hp = 100;
        private double regen = 1.0;
        private double armorReduction = 0.0;

        private double moveSpeed = 240;
        private double damageMultiplier = 1.0;
        private double attackSpeedMultiplier = 1.0;
        private double projectileSpeedMultiplier = 1.0;
        private double projectileSizeMultiplier = 1.0;
        private double magnetRadius = 105;
        private double shotCooldown = 0.2;
        private double tripleCooldown = 0.8;
        private double pulseCooldown = 1.2;
        private double lanceCooldown = 0.6;
        private double lightningCooldown = 1.1;
        private double sawCooldown = 0.7;
        private double auraTickCooldown = 0.2;
        private double auraRadius = 82;
        private double flatDamageBonus = 0.0;

        private int level = 1;
        private int xp = 0;
        private int xpToNext = 24;

        private void reset() {
            x = 640;
            y = 360;
            maxHp = 100;
            hp = 100;
            regen = 1.0;
            armorReduction = 0;
            moveSpeed = 240;
            damageMultiplier = 1.0;
            attackSpeedMultiplier = 1.0;
            projectileSpeedMultiplier = 1.0;
            projectileSizeMultiplier = 1.0;
            magnetRadius = 105;
            shotCooldown = 0.2;
            tripleCooldown = 0.8;
            pulseCooldown = 1.2;
            lanceCooldown = 0.6;
            lightningCooldown = 1.1;
            sawCooldown = 0.7;
            auraTickCooldown = 0.2;
            auraRadius = 82;
            flatDamageBonus = 0.0;
            level = 1;
            xp = 0;
            xpToNext = 24;
        }

        private void heal(double dt) {
            hp = Math.min(maxHp, hp + regen * dt);
        }

        private void updateMovement(double dt, boolean up, boolean down, boolean left, boolean right, int maxX, int maxY) {
            double dx = 0;
            double dy = 0;
            if (up) dy -= 1;
            if (down) dy += 1;
            if (left) dx -= 1;
            if (right) dx += 1;

            if (dx != 0 || dy != 0) {
                double len = Math.hypot(dx, dy);
                x += (dx / len) * moveSpeed * dt;
                y += (dy / len) * moveSpeed * dt;
                x = Math.max(radius, Math.min(maxX - radius, x));
                y = Math.max(radius, Math.min(maxY - radius, y));
            }
        }
    }

    private static final class Enemy {
        private double x;
        private double y;
        private final double radius;
        private double hp;
        private final double speed;
        private final double contactDamage;
        private final int xpValue;
        private final int score;

        private Enemy(double x, double y, double radius, double hp, double speed, double contactDamage, int xpValue) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.hp = hp;
            this.speed = speed;
            this.contactDamage = contactDamage;
            this.xpValue = xpValue;
            this.score = xpValue * 3;
        }
    }

    private static final class Projectile {
        private double x;
        private double y;
        private final double vx;
        private final double vy;
        private final double damage;
        private final double radius;
        private int pierce;
        private double life = 1.8;

        private Projectile(double x, double y, double vx, double vy, double damage, double radius, int pierce) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.damage = damage;
            this.radius = radius;
            this.pierce = pierce;
        }
    }

    private static final class XpOrb {
        private double x;
        private double y;
        private final int value;

        private XpOrb(double x, double y, int value) {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }
}
