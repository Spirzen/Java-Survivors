package com.diabloid;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class DiabloidGame {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("JavaSurvivors");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.setContentPane(new com.diabloid.GamePanel());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    static final class GamePanel extends JPanel implements Runnable {
        private static Image playerSprite;
        private static Image enemyNormalSprite;
        private static Image enemyTankSprite;
        private static Image bgSprite;
        private static boolean imagesLoaded = false;
        private static final int WIDTH = 1280;
        private static final int HEIGHT = 720;
        private static final double CONTACT_DAMAGE_TICK = 0.25;
        private static final double DASH_SPEED_MULTIPLIER = 4.5;
        private static final double DASH_DURATION = 0.15;
        private static final double DASH_COOLDOWN = 1.5;

        final Random rng = new Random();
        private final Thread gameThread;

        boolean up;
        boolean down;
        boolean left;
        boolean right;

        private boolean isDashing = false;
        private double dashTimer = 0;
        private double dashCooldownTimer = 0;
        private double lastDashX = 640;
        private double lastDashY = 360;

        private boolean running = true;
        GameState gameState = GameState.MENU;
        UpgradeState upgradeState = UpgradeState.NONE;

        final Player player = new Player();
        private final List<Enemy> enemies = new ArrayList<>();
        private final List<Projectile> projectiles = new ArrayList<>();
        private final List<XpOrb> xpOrbs = new ArrayList<>();
        private final List<DamageNumber> damageNumbers = new ArrayList<>();
        private final List<StatusEffect> playerEffects = new ArrayList<>();
        final List<Particle> particles = new ArrayList<>();
        private final List<String> upgradeChoices = new ArrayList<>();
        private final List<String> shopChoices = new ArrayList<>();
        private final Set<WeaponType> unlockedWeapons = new HashSet<>();
        private final Set<String> permanentShopUpgrades = new HashSet<>();
        private final EnumMap<PerkBranch, List<String>> branchUpgrades = new EnumMap<>(PerkBranch.class);
        private final EnumMap<WeaponType, Double> extraWeaponCooldowns = new EnumMap<>(WeaponType.class);

        // Специальные эффекты для отрисовки
        private final List<LightningEffect> chainLightnings = new ArrayList<>();
        private final List<SawBladeEffect> sawBlades = new ArrayList<>();
        private final WeaponManager weaponManager = new WeaponManager(this);
        private final EnemySpawner enemySpawner = new EnemySpawner(this);
        private final ParticleSystem particleSystem = new ParticleSystem(this);
        private final SaveSystem saveSystem = new SaveSystem();
        private SaveData saveData = new SaveData();

        private double worldTime;
        private double spawnTimer;
        private double contactDamageTimer;
        private int score;
        private int pendingLevelUps;
        private int wave;
        private double waveTimer;
        private double bossTimer = 180.0;
        private int shotKillStreak;
        private double noDamageTime;
        private int selectedMenuAction = 0; // 0 - новая, 1 - загрузка
        private int selectedCharacterIdx = 0;
        private boolean characterSelectActive = false;
        private final List<CharacterDef> characters = new ArrayList<>();
        private int runCoins = 0;
        private final List<CoinPickup> mapCoins = new ArrayList<>();
        private double coinSpawnTimer = 5.0;

        GamePanel() {
            loadAssets();
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setFocusable(true);
            setBackground(new Color(17, 17, 20));
            addKeyListener(new KeyHandler());

            addMouseListener(new MouseHandler());
            addMouseMotionListener(new MouseHandler());
            initBranchUpgradePools();
            initCharacters();
            saveData = saveSystem.load();

            gameThread = new Thread(this, "game-loop");
            gameThread.start();
        }

        private void loadAssets() {
            if (imagesLoaded) return;

            Toolkit toolkit = Toolkit.getDefaultToolkit();

            try {
                playerSprite = toolkit.getImage("assets/player.png");
                enemyNormalSprite = toolkit.getImage("assets/enemy_normal.png");
                enemyTankSprite = toolkit.getImage("assets/enemy_tank.png");
                bgSprite = toolkit.getImage("assets/background.jpg");

                MediaTracker tracker = new MediaTracker(new JPanel());
                tracker.addImage(playerSprite, 0);
                tracker.addImage(enemyNormalSprite, 1);
                tracker.addImage(enemyTankSprite, 2);
                tracker.addImage(bgSprite, 3);
                tracker.waitForAll();

                imagesLoaded = true;
            } catch (Exception e) {
                System.err.println("Ошибка загрузки спрайтов: " + e.getMessage());
                imagesLoaded = false;
            }
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
            if (upgradeState == UpgradeState.PAUSED_FOR_SHOP) {
                return;
            }

            worldTime += dt;
            waveTimer += dt;
            noDamageTime += dt;
            saveData.bestNoDamageSeconds = Math.max(saveData.bestNoDamageSeconds, noDamageTime);
            updateWaveProgress();

            if (isDashing) {
                dashTimer -= dt;
                if (dashTimer <= 0) {
                    isDashing = false;
                    lastDashX = player.x;
                    lastDashY = player.y;
                }
            } else {
                dashCooldownTimer -= dt;
            }

            player.heal(dt);
            player.updateMovement(dt, up, down, left, right, WIDTH, HEIGHT);
            updatePlayerStatusEffects(dt);
            particleSystem.spawnFootsteps(dt);
            particleSystem.updateParticles(dt);
            updateEnemyStatusEffects(dt);

            // Обновление активных эффектов
            updateChainLightning(dt);
            updateSawBlades(dt);

            weaponManager.updateWeapons(dt);
            enemySpawner.spawnEnemies(dt);
            updateEnemies(dt);
            updateProjectiles(dt);
            updateXpOrbs(dt);
            updateCoins(dt);
            updateDamageNumbers(dt);
        }

        private void updateCoins(double dt) {
            coinSpawnTimer -= dt;
            if (coinSpawnTimer <= 0) {
                coinSpawnTimer = 4.0 + rng.nextDouble() * 4.0;
                mapCoins.add(new CoinPickup(30 + rng.nextDouble() * (WIDTH - 60), 30 + rng.nextDouble() * (HEIGHT - 60), 1 + rng.nextInt(4)));
            }
            Iterator<CoinPickup> it = mapCoins.iterator();
            while (it.hasNext()) {
                CoinPickup c = it.next();
                if (distanceSq(c.x, c.y, player.x, player.y) <= (player.radius + 8) * (player.radius + 8)) {
                    it.remove();
                    runCoins += c.value;
                    saveData.totalCoins += c.value;
                }
            }
        }

        private void initBranchUpgradePools() {
            branchUpgrades.put(PerkBranch.ATTACK, List.of(
                    "Сила +20%", "Скорость атаки +20%", "Урон +5", "Кол-во снарядов +1",
                    "Оружие: Ледяная волна", "Оружие: Токсичный дротик", "Оружие: Огненный шар"
            ));
            branchUpgrades.put(PerkBranch.DEFENSE, List.of(
                    "Макс. HP +20", "Броня +8%", "Щит +30", "Регенерация +0.5"
            ));
            branchUpgrades.put(PerkBranch.SUPPORT, List.of(
                    "Скорость движения +15%", "Магнит +20%", "Скорость снаряда +20%", "Размер снаряда +20%"
            ));
        }

        private void initCharacters() {
            if (!characters.isEmpty()) return;
            characters.add(new CharacterDef("Astra", 0, WeaponType.MAGIC_BOLT, 1.0, 1.0));
            characters.add(new CharacterDef("Vulcan", 100, WeaponType.FLAME_ORB, 1.12, 0.95));
            characters.add(new CharacterDef("Glacia", 250, WeaponType.FROST_NOVA, 1.0, 1.08));
            characters.add(new CharacterDef("Venom", 500, WeaponType.TOXIC_DART, 0.95, 1.15));
            characters.add(new CharacterDef("Storm", 900, WeaponType.CHAIN_LIGHTNING, 1.10, 1.0));
            characters.add(new CharacterDef("Blade", 1400, WeaponType.SAW_BLADE, 1.18, 0.92));
            characters.add(new CharacterDef("Titan", 2200, WeaponType.PIERCE_LANCE, 1.25, 0.82));
            characters.add(new CharacterDef("Aura", 3400, WeaponType.DAMAGE_AURA, 0.9, 1.2));
            characters.add(new CharacterDef("Pulse", 5500, WeaponType.PULSE_RING, 1.05, 1.05));
            characters.add(new CharacterDef("Oracle", 10000, WeaponType.TRIPLE_CAST, 1.15, 1.12));
            if (saveData.unlockedCharacters.isEmpty()) {
                saveData.unlockedCharacters.add("Astra");
            }
        }

        private void updateWaveProgress() {
            bossTimer -= 1.0 / 120.0;
            if (bossTimer <= 0) {
                bossTimer = 180.0;
                enemies.add(spawnBoss());
            }
            if (waveTimer >= 30.0) {
                waveTimer = 0;
                wave++;
                if (wave % 3 == 0) {
                    rollShopChoices();
                    upgradeState = UpgradeState.PAUSED_FOR_SHOP;
                }
            }
        }

        private Enemy spawnBoss() {
            Enemy boss = new Enemy(WIDTH * 0.5, -80, 38, 1800 + wave * 120, 68, 14 + wave * 1.2, 45);
            boss.kind = EnemyKind.BOSS;
            boss.score = 600 + wave * 40;
            return boss;
        }

        void updateWeapons(double dt) {
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
            if (unlockedWeapons.contains(WeaponType.FROST_NOVA)) {
                player.frostCooldown -= dt;
                if (player.frostCooldown <= 0) {
                    shootFrostNova();
                    player.frostCooldown = Math.max(0.25, 2.10 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.TOXIC_DART)) {
                player.toxicCooldown -= dt;
                if (player.toxicCooldown <= 0) {
                    shootToxicDart();
                    player.toxicCooldown = Math.max(0.14, 0.72 / player.attackSpeedMultiplier);
                }
            }
            if (unlockedWeapons.contains(WeaponType.FLAME_ORB)) {
                player.flameCooldown -= dt;
                if (player.flameCooldown <= 0) {
                    shootFlameOrb();
                    player.flameCooldown = Math.max(0.18, 0.95 / player.attackSpeedMultiplier);
                }
            }
            updateExtraWeapons(dt);
        }

        private void updateExtraWeapons(double dt) {
            for (WeaponType weapon : unlockedWeapons) {
                if (!isExtraWeapon(weapon)) continue;
                double cd = extraWeaponCooldowns.getOrDefault(weapon, 0.1) - dt;
                if (cd <= 0) {
                    fireExtraWeapon(weapon);
                    cd = Math.max(0.20, 1.35 / player.attackSpeedMultiplier);
                }
                extraWeaponCooldowns.put(weapon, cd);
            }
        }

        private boolean isExtraWeapon(WeaponType weapon) {
            return weapon.ordinal() >= WeaponType.FIRE_WAVE.ordinal();
        }

        private void fireExtraWeapon(WeaponType weapon) {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;
            Projectile p = projectileTowards(target, 13.5, 5.5, 560.0, 1);
            if (p == null) return;
            switch (weapon) {
                case FIRE_WAVE, FIRE_LANCE, FIRE_METEOR -> p.appliesBurn = true;
                case ICE_SHARD, ICE_SPIKE, ICE_STORM -> p.appliesSlow = true;
                case WATER_JET, WATER_ORB, WATER_TIDE -> p.life = 2.4;
                case EARTH_SPIKE, EARTH_QUAKE, EARTH_BLADE -> p.radius *= 1.35;
                case THUNDER_SPEAR, THUNDER_FIELD -> p.damage *= 1.20;
                case SHADOW_SCYTHE -> p.pierce = 3;
                default -> { }
            }
            projectiles.add(p);
        }

        private void shootMagicBolt() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;

            // Логика: 1 базовый снаряд + бонус от multishot
            // multishotMultiplier по умолчанию 0.0, значит выстрел один.
            // Если выбрано улучшение (+1), то totalShots = 2.
            int baseCount = 1;
            int totalShots = (int) Math.round(baseCount + player.multishotMultiplier);

            // Если multishot > 0, делаем веерный выстрел, иначе один прямой
            if (totalShots > 1) {
                double dx = target.x - player.x;
                double dy = target.y - player.y;
                double len = Math.hypot(dx, dy);
                if (len < 0.001) return;

                double baseAngle = Math.atan2(dy, dx);
                double totalSpread = 0.3; // Угол разброса
                double angleStep = totalSpread / (totalShots - 1);

                for (int i = 0; i < totalShots; i++) {
                    double offset = (i - (totalShots - 1) / 2.0) * angleStep;
                    double angle = baseAngle + offset;

                    double speed = 600.0 * player.projectileSpeedMultiplier;
                    double vx = Math.cos(angle) * speed;
                    double vy = Math.sin(angle) * speed;

                    double radius = 6.0 * player.projectileSizeMultiplier;
                    double damage = (18.0 * totalDamageMultiplier()) + player.flatDamageBonus;

                    projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 0));
                }
            } else {
                spawnProjectileTowards(target, 18.0, 6.0, 600.0, 0);
            }
        }

        private void shootTripleCast() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;

            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double baseAngle = Math.atan2(dy, dx);

            int baseCount = 3;
            int totalShots = (int) Math.round(baseCount + player.multishotMultiplier);

            double totalSpread = 0.44;
            double angleStep = totalSpread / (totalShots - 1);

            for (int i = 0; i < totalShots; i++) {
                double offset = (i - (totalShots - 1) / 2.0) * angleStep;
                double angle = baseAngle + offset;

                double speed = 540.0 * player.projectileSpeedMultiplier;
                double vx = Math.cos(angle) * speed;
                double vy = Math.sin(angle) * speed;

                double radius = 5.5 * player.projectileSizeMultiplier;
                double damage = (12.0 * totalDamageMultiplier()) + player.flatDamageBonus;

                projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 0));
            }
        }

        private void shootPulseRing() {
            shotKillStreak = 0;
            int baseCount = 10;
            int totalCount = (int) Math.round(baseCount + player.multishotMultiplier);

            for (int i = 0; i < totalCount; i++) {
                double angle = (Math.PI * 2.0 / totalCount) * i;

                double speed = 430.0 * player.projectileSpeedMultiplier;
                double vx = Math.cos(angle) * speed;
                double vy = Math.sin(angle) * speed;

                double radius = 5.0 * player.projectileSizeMultiplier;
                double damage = (14.0 * totalDamageMultiplier()) + player.flatDamageBonus;

                projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 0));
            }
        }

        private void shootPierceLance() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;

            // Пращающее копье теперь тоже может быть множественным
            int baseCount = 1;
            int totalShots = (int) Math.round(baseCount + player.multishotMultiplier);

            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double len = Math.hypot(dx, dy);
            if (len < 0.001) return;

            double baseAngle = Math.atan2(dy, dx);
            double totalSpread = 0.2;
            double angleStep = totalSpread / Math.max(1, totalShots - 1);

            for (int i = 0; i < totalShots; i++) {
                double offset = (i - (totalShots - 1) / 2.0) * angleStep;
                double angle = baseAngle + offset;

                double speed = 760.0 * player.projectileSpeedMultiplier;
                double vx = Math.cos(angle) * speed;
                double vy = Math.sin(angle) * speed;

                double radius = 7.0 * player.projectileSizeMultiplier;
                double damage = (24.0 * totalDamageMultiplier()) + player.flatDamageBonus;

                // Копья имеют pierce
                projectiles.add(new Projectile(player.x, player.y, vx, vy, damage, radius, 2));
            }
        }

        private void updateDamageAura(double dt) {
            player.auraTickCooldown -= dt;
            if (player.auraTickCooldown > 0) {
                return;
            }
            player.auraTickCooldown = 0.28;
            double auraRadius = player.auraRadius * player.projectileSizeMultiplier;
            double auraDamage = (7.5 * totalDamageMultiplier()) + player.flatDamageBonus * 0.6;
            for (Enemy enemy : new ArrayList<>(enemies)) {
                if (distanceSq(player.x, player.y, enemy.x, enemy.y) <= auraRadius * auraRadius) {
                    damageEnemy(enemy, auraDamage);
                }
            }
        }

        private void castChainLightning() {
            shotKillStreak = 0;
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

            // Создаем эффект молнии для отрисовки с передачей всех целей
            chainLightnings.add(new LightningEffect(first, second, third));

            double baseDamage = (26.0 * totalDamageMultiplier()) + player.flatDamageBonus;
            damageEnemy(first, baseDamage);
            if (second != null) damageEnemy(second, baseDamage * 0.72);
            if (third != null) damageEnemy(third, baseDamage * 0.5);
        }

        private void updateChainLightning(double dt) {
            Iterator<LightningEffect> it = chainLightnings.iterator();
            while (it.hasNext()) {
                LightningEffect le = it.next();
                le.life -= dt;
                if (le.life <= 0) {
                    it.remove();
                }
            }
        }

        private void shootSawBlade() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;

            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double baseAngle = Math.atan2(dy, dx);

            int baseCount = 1;
            int totalShots = (int) Math.round(baseCount + player.multishotMultiplier);

            double totalSpread = 0.5;
            double angleStep = totalSpread / Math.max(1, totalShots - 1);

            for (int i = 0; i < totalShots; i++) {
                double offset = (i - (totalShots - 1) / 2.0) * angleStep;
                double angle = baseAngle + offset;

                double speed = 500.0 * player.projectileSpeedMultiplier;
                double radius = 8.0 * player.projectileSizeMultiplier;
                double damage = (20.0 * totalDamageMultiplier()) + player.flatDamageBonus;

                // Добавляем эффект пилы для отрисовки
                sawBlades.add(new SawBladeEffect(player.x, player.y, angle, speed, radius, damage, 5));
            }
        }

        private void updateSawBlades(double dt) {
            // Используем обратный цикл или копию, чтобы избежать ConcurrentModificationException
            // при удалении врагов внутри цикла.
            // Но так как мы удаляем из 'enemies', а итерируемся по 'sawBlades', проблема возникает,
            // если мы пытаемся изменить структуру 'sawBlades' или если 'damageEnemy' вызывает удаление из 'enemies',
            // которое конфликтует с внутренними механизмами Java Collections при одновременной итерации.
            // Здесь safest way - скопировать список врагов перед проверкой.

            List<Enemy> currentEnemies = new ArrayList<>(enemies);

            Iterator<SawBladeEffect> it = sawBlades.iterator();
            while (it.hasNext()) {
                SawBladeEffect sb = it.next();
                sb.life -= dt;
                sb.angle += dt * 15.0; // Вращение

                // Движение вперед
                double moveVx = Math.cos(sb.angle) * sb.speed;
                double moveVy = Math.sin(sb.angle) * sb.speed;
                sb.x += moveVx * dt;
                sb.y += moveVy * dt;

                // Проверка столкновений для пилы (по копии списка врагов)
                for (Enemy e : currentEnemies) {
                    if (e.hp <= 0) continue; // Пропускаем мертвых
                    if (distanceSq(sb.x, sb.y, e.x, e.y) <= (sb.radius + e.radius) * (sb.radius + e.radius)) {
                        damageEnemy(e, sb.damage);
                        sb.pierce--;
                    }
                }

                if (sb.life <= 0 || sb.pierce <= 0 || sb.x < -100 || sb.y < -100 || sb.x > WIDTH + 100 || sb.y > HEIGHT + 100) {
                    it.remove();
                }
            }
        }

        private void shootFrostNova() {
            shotKillStreak = 0;
            int count = 12;
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2 / count) * i;
                double speed = 360.0 * player.projectileSpeedMultiplier;
                Projectile p = new Projectile(player.x, player.y, Math.cos(angle) * speed, Math.sin(angle) * speed,
                        (10.0 * totalDamageMultiplier()) + player.flatDamageBonus, 5.2 * player.projectileSizeMultiplier, 0);
                p.appliesSlow = true;
                p.life = 0.85;
                projectiles.add(p);
            }
        }

        private void shootToxicDart() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;
            Projectile p = projectileTowards(target, 11.0, 4.5, 760.0, 1);
            if (p != null) {
                p.appliesPoison = true;
                projectiles.add(p);
            }
        }

        private void shootFlameOrb() {
            shotKillStreak = 0;
            Enemy target = findNearestEnemy();
            if (target == null) return;
            Projectile p = projectileTowards(target, 16.0, 6.8, 520.0, 0);
            if (p != null) {
                p.appliesBurn = true;
                p.life = 2.2;
                projectiles.add(p);
            }
        }

        private Projectile projectileTowards(Enemy target, double baseDamage, double baseRadius, double baseSpeed, int pierce) {
            double dx = target.x - player.x;
            double dy = target.y - player.y;
            double len = Math.hypot(dx, dy);
            if (len < 0.001) return null;
            double speed = baseSpeed * player.projectileSpeedMultiplier;
            double radius = baseRadius * player.projectileSizeMultiplier;
            double damage = (baseDamage * totalDamageMultiplier()) + player.flatDamageBonus;
            return new Projectile(player.x, player.y, (dx / len) * speed, (dy / len) * speed, damage, radius, pierce);
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
            double damage = (baseDamage * totalDamageMultiplier()) + player.flatDamageBonus;
            projectiles.add(new Projectile(player.x, player.y, (dx / len) * speed, (dy / len) * speed, damage, radius, pierce));
        }

        private double totalDamageMultiplier() {
            return player.damageMultiplier * (1.0 + player.tempDamageBoost);
        }

        void spawnEnemies(double dt) {
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
            double roll = rng.nextDouble();
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

            if (roll < 0.12) {
                Enemy e = new Enemy(x, y, 10, 26 * difficulty, 190 + difficulty * 14, 8 + difficulty * 1.8, 2 + (int) (difficulty * 0.7));
                e.kind = EnemyKind.SPEEDER;
                return e;
            } else if (roll < 0.26) {
                Enemy e = new Enemy(x, y, 18, 55 * difficulty, 92 + difficulty * 8, 4.2 + difficulty * 1.4, 3 + (int) (difficulty * 0.9));
                e.kind = EnemyKind.SHOOTER;
                return e;
            } else if (roll < 0.48) {
                Enemy e = new Enemy(x, y, 22, 95 * difficulty, 62 + difficulty * 5, 5 + difficulty * 2, 4 + (int) difficulty);
                e.kind = EnemyKind.TANK;
                return e;
            } else {
                Enemy e = new Enemy(x, y, 14, 40 * difficulty, 105 + difficulty * 8, 3 + difficulty, 2 + (int) (difficulty * 0.8));
                e.kind = EnemyKind.NORMAL;
                return e;
            }
        }

        private void updateEnemies(double dt) {
            for (Enemy e : enemies) {
                e.attackCooldown -= dt;
                if (e.kind == EnemyKind.BOSS) {
                    if (e.hp < 1200) e.phase = 2;
                    if (e.hp < 600) e.phase = 3;
                    if (e.attackCooldown <= 0) {
                        if (e.phase == 1) {
                            // Взрыв-волна по области
                            for (int i = 0; i < 18; i++) {
                                double a = (Math.PI * 2 / 18) * i;
                                Projectile ep = new Projectile(e.x, e.y, Math.cos(a) * 260, Math.sin(a) * 260, 9 + wave * 0.6, 4, 0);
                                ep.fromEnemy = true;
                                projectiles.add(ep);
                            }
                            e.attackCooldown = 4.0;
                        } else if (e.phase == 2) {
                            enemies.add(spawnEnemy(1.0 + worldTime * 0.01));
                            enemies.add(spawnEnemy(1.0 + worldTime * 0.01));
                            e.attackCooldown = 5.5;
                        } else {
                            for (int i = 0; i < 4; i++) enemies.add(spawnEnemy(1.1 + worldTime * 0.012));
                            e.attackCooldown = 6.0;
                        }
                    }
                }
                double dx = player.x - e.x;
                double dy = player.y - e.y;
                double len = Math.hypot(dx, dy);
                if (len > 0.0001 && e.kind != EnemyKind.SHOOTER) {
                    e.x += (dx / len) * e.speed * e.speedMultiplier * dt;
                    e.y += (dy / len) * e.speed * e.speedMultiplier * dt;
                } else if (e.kind == EnemyKind.SHOOTER) {
                    double preferred = 180;
                    if (len > preferred + 20) {
                        e.x += (dx / len) * e.speed * 0.75 * dt;
                        e.y += (dy / len) * e.speed * 0.75 * dt;
                    } else if (len < preferred - 20) {
                        e.x -= (dx / len) * e.speed * 0.75 * dt;
                        e.y -= (dy / len) * e.speed * 0.75 * dt;
                    }
                    if (e.attackCooldown <= 0 && len > 50) {
                        Projectile ep = new Projectile(e.x, e.y, (dx / len) * 300, (dy / len) * 300, 7.0 + wave * 0.3, 3.0, 0);
                        ep.fromEnemy = true;
                        projectiles.add(ep);
                        e.attackCooldown = 1.3;
                    }
                }
                e.updateAnimation(dt);
                if (distanceSq(e.x, e.y, player.x, player.y) <= (e.radius + player.radius + 14) * (e.radius + player.radius + 14)) {
                    e.attackWindup = Math.min(1.0, e.attackWindup + dt * 3.5);
                } else {
                    e.attackWindup = Math.max(0.0, e.attackWindup - dt * 2.0);
                }
            }

            contactDamageTimer -= dt;
            if (contactDamageTimer <= 0) {
                double sumDamage = 0;
                for (Enemy e : enemies) {
                    if (!isDashing && distanceSq(e.x, e.y, player.x, player.y) <= (e.radius + player.radius) * (e.radius + player.radius)) {
                        sumDamage += e.contactDamage;
                    }
                }
                if (sumDamage > 0) {
                    double incoming = sumDamage * (1.0 - player.armorReduction);
                    incoming = absorbShieldDamage(incoming);
                    player.hp -= incoming;
                    noDamageTime = 0;
                    if (player.hp <= 0) {
                        player.hp = 0;
                        gameState = GameState.GAME_OVER;
                        saveData.bestNoDamageSeconds = Math.max(saveData.bestNoDamageSeconds, noDamageTime);
                        saveData.savedRunCoins = runCoins;
                        saveData.savedScore = score;
                        saveData.savedWave = wave;
                        saveData.savedWorldTime = worldTime;
                        saveSystem.save(saveData);
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

                if (p.fromEnemy) {
                    if (!isDashing && distanceSq(p.x, p.y, player.x, player.y) <= (p.radius + player.radius) * (p.radius + player.radius)) {
                        double incoming = absorbShieldDamage(p.damage * (1.0 - player.armorReduction));
                        player.hp -= incoming;
                        noDamageTime = 0;
                        particleSystem.spawnProjectileHitSparks(p.x, p.y);
                        pi.remove();
                        if (player.hp <= 0) {
                            player.hp = 0;
                            gameState = GameState.GAME_OVER;
                            saveData.savedRunCoins = runCoins;
                            saveData.savedScore = score;
                            saveData.savedWave = wave;
                            saveData.savedWorldTime = worldTime;
                            saveSystem.save(saveData);
                        }
                    }
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
                    particleSystem.spawnProjectileHitSparks(p.x, p.y);
                    if (p.appliesSlow) {
                        addEnemyStatus(hit, new StatusEffect(StatusEffectType.SLOW, 2.5, 0.0, 0.45));
                    }
                    if (p.appliesBurn) {
                        addEnemyStatus(hit, new StatusEffect(StatusEffectType.BURN, 3.0, 7.0 * player.damageMultiplier, 0.0));
                    }
                    if (p.appliesPoison) {
                        addPlayerStatus(new StatusEffect(StatusEffectType.POISON, 3.5, 0.0, 0.5));
                    }
                    if (p.pierce > 0) {
                        p.pierce--;
                    } else {
                        pi.remove();
                    }
                }
            }
        }

        private void damageEnemy(Enemy enemy, double damage) {
            enemy.takeDamage(damage);
            damageNumbers.add(new DamageNumber(enemy.x, enemy.y - enemy.radius, (int) damage));

            if (enemy.hp <= 0) {
                enemies.remove(enemy);
                score += enemy.score;
                xpOrbs.add(new XpOrb(enemy.x, enemy.y, enemy.xpValue));
                shotKillStreak++;
                particleSystem.spawnKillExplosion(enemy.x, enemy.y);
                saveData.bestMultiKill = Math.max(saveData.bestMultiKill, shotKillStreak);
                saveData.highScore = Math.max(saveData.highScore, score);
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
                        player.xpToNext = (int) Math.round(player.xpToNext * 1.15 + 2);
                        pendingLevelUps++;
                    }
                    if (pendingLevelUps > 0 && upgradeState == UpgradeState.NONE) {
                        rollUpgradeChoices();
                        upgradeState = UpgradeState.PAUSED_FOR_UPGRADE;
                    }
                }
            }
        }

        private void updateDamageNumbers(double dt) {
            Iterator<DamageNumber> di = damageNumbers.iterator();
            while (di.hasNext()) {
                DamageNumber dn = di.next();
                dn.y -= 15.0 * dt;
                dn.alpha -= 2.0 * dt;

                if (dn.alpha <= 0) {
                    di.remove();
                }
            }
        }

        private void rollUpgradeChoices() {
            upgradeChoices.clear();
            for (PerkBranch branch : PerkBranch.values()) {
                List<String> pool = new ArrayList<>(branchUpgrades.get(branch));
                addMissingWeaponsToPool(pool);
                if (!pool.isEmpty()) {
                    String pick = pool.get(rng.nextInt(pool.size()));
                    upgradeChoices.add(branch.title + ": " + pick);
                }
            }
        }

        private void addMissingWeaponsToPool(List<String> pool) {
            if (!unlockedWeapons.contains(WeaponType.TRIPLE_CAST)) pool.add("Оружие: Тройной залп");
            if (!unlockedWeapons.contains(WeaponType.PULSE_RING)) pool.add("Оружие: Кольцо импульса");
            if (!unlockedWeapons.contains(WeaponType.PIERCE_LANCE)) pool.add("Оружие: Пронзающее копье");
            if (!unlockedWeapons.contains(WeaponType.DAMAGE_AURA)) pool.add("Оружие: Аура боли");
            if (!unlockedWeapons.contains(WeaponType.CHAIN_LIGHTNING)) pool.add("Оружие: Цепная молния");
            if (!unlockedWeapons.contains(WeaponType.SAW_BLADE)) pool.add("Оружие: Пила");
            if (!unlockedWeapons.contains(WeaponType.FIRE_WAVE)) pool.add("Оружие: Огненная волна");
            if (!unlockedWeapons.contains(WeaponType.FIRE_LANCE)) pool.add("Оружие: Огненное копье");
            if (!unlockedWeapons.contains(WeaponType.FIRE_METEOR)) pool.add("Оружие: Метеор");
            if (!unlockedWeapons.contains(WeaponType.ICE_SHARD)) pool.add("Оружие: Ледяной осколок");
            if (!unlockedWeapons.contains(WeaponType.ICE_SPIKE)) pool.add("Оружие: Ледяной шип");
            if (!unlockedWeapons.contains(WeaponType.ICE_STORM)) pool.add("Оружие: Ледяной шторм");
            if (!unlockedWeapons.contains(WeaponType.WATER_JET)) pool.add("Оружие: Водяной поток");
            if (!unlockedWeapons.contains(WeaponType.WATER_ORB)) pool.add("Оружие: Водяная сфера");
            if (!unlockedWeapons.contains(WeaponType.WATER_TIDE)) pool.add("Оружие: Прилив");
            if (!unlockedWeapons.contains(WeaponType.EARTH_SPIKE)) pool.add("Оружие: Каменный шип");
            if (!unlockedWeapons.contains(WeaponType.EARTH_QUAKE)) pool.add("Оружие: Землетрясение");
            if (!unlockedWeapons.contains(WeaponType.EARTH_BLADE)) pool.add("Оружие: Земляной клинок");
            if (!unlockedWeapons.contains(WeaponType.THUNDER_SPEAR)) pool.add("Оружие: Громовое копье");
            if (!unlockedWeapons.contains(WeaponType.THUNDER_FIELD)) pool.add("Оружие: Грозовое поле");
            if (!unlockedWeapons.contains(WeaponType.SHADOW_SCYTHE)) pool.add("Оружие: Теневая коса");
        }

        private void applyUpgrade(int idx) {
            if (idx < 0 || idx >= upgradeChoices.size()) {
                return;
            }
            String picked = upgradeChoices.get(idx);
            for (PerkBranch branch : PerkBranch.values()) {
                if (picked.startsWith(branch.title + ": ")) {
                    picked = picked.substring((branch.title + ": ").length());
                    break;
                }
            }
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
            } else if (picked.startsWith("Кол-во снарядов")) {
                player.multishotMultiplier += 1.0;
            } else if (picked.startsWith("Щит +30")) {
                addPlayerStatus(new StatusEffect(StatusEffectType.SHIELD, 12.0, 30.0, 0.0));
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
            } else if (picked.contains("Ледяная волна")) {
                unlockedWeapons.add(WeaponType.FROST_NOVA);
            } else if (picked.contains("Токсичный дротик")) {
                unlockedWeapons.add(WeaponType.TOXIC_DART);
            } else if (picked.contains("Огненный шар")) {
                unlockedWeapons.add(WeaponType.FLAME_ORB);
            } else if (picked.contains("Огненная волна")) {
                unlockedWeapons.add(WeaponType.FIRE_WAVE);
            } else if (picked.contains("Огненное копье")) {
                unlockedWeapons.add(WeaponType.FIRE_LANCE);
            } else if (picked.contains("Метеор")) {
                unlockedWeapons.add(WeaponType.FIRE_METEOR);
            } else if (picked.contains("Ледяной осколок")) {
                unlockedWeapons.add(WeaponType.ICE_SHARD);
            } else if (picked.contains("Ледяной шип")) {
                unlockedWeapons.add(WeaponType.ICE_SPIKE);
            } else if (picked.contains("Ледяной шторм")) {
                unlockedWeapons.add(WeaponType.ICE_STORM);
            } else if (picked.contains("Водяной поток")) {
                unlockedWeapons.add(WeaponType.WATER_JET);
            } else if (picked.contains("Водяная сфера")) {
                unlockedWeapons.add(WeaponType.WATER_ORB);
            } else if (picked.contains("Прилив")) {
                unlockedWeapons.add(WeaponType.WATER_TIDE);
            } else if (picked.contains("Каменный шип")) {
                unlockedWeapons.add(WeaponType.EARTH_SPIKE);
            } else if (picked.contains("Землетрясение")) {
                unlockedWeapons.add(WeaponType.EARTH_QUAKE);
            } else if (picked.contains("Земляной клинок")) {
                unlockedWeapons.add(WeaponType.EARTH_BLADE);
            } else if (picked.contains("Громовое копье")) {
                unlockedWeapons.add(WeaponType.THUNDER_SPEAR);
            } else if (picked.contains("Грозовое поле")) {
                unlockedWeapons.add(WeaponType.THUNDER_FIELD);
            } else if (picked.contains("Теневая коса")) {
                unlockedWeapons.add(WeaponType.SHADOW_SCYTHE);
            }

            pendingLevelUps--;
            if (pendingLevelUps > 0) {
                rollUpgradeChoices();
            } else {
                upgradeState = UpgradeState.NONE;
                upgradeChoices.clear();
            }
        }

        private void rollShopChoices() {
            shopChoices.clear();
            shopChoices.add("Временный бафф: Сила +35% (на 20с) [160 очков]");
            shopChoices.add("Временный бафф: Щит +50 (на 15с) [180 очков]");
            if (!permanentShopUpgrades.contains("PERM_REGEN")) {
                shopChoices.add("Постоянно: Регенерация +0.4 [300 очков]");
            } else {
                shopChoices.add("Постоянно: Броня +4% [320 очков]");
            }
        }

        private void applyShopChoice(int idx) {
            if (idx < 0 || idx >= shopChoices.size()) return;
            String choice = shopChoices.get(idx);
            int cost = choice.contains("[300") ? 300 : choice.contains("[320") ? 320 : choice.contains("[180") ? 180 : 160;
            if (score < cost) return;
            score -= cost;
            if (choice.contains("Сила")) {
                addPlayerStatus(new StatusEffect(StatusEffectType.TEMP_DAMAGE_BOOST, 20.0, 0.35, 0.0));
            } else if (choice.contains("Щит")) {
                addPlayerStatus(new StatusEffect(StatusEffectType.SHIELD, 15.0, 50.0, 0.0));
            } else if (choice.contains("Регенерация")) {
                permanentShopUpgrades.add("PERM_REGEN");
                player.regen += 0.4;
            } else if (choice.contains("Броня")) {
                player.armorReduction = Math.min(0.80, player.armorReduction + 0.04);
            }
            upgradeState = UpgradeState.NONE;
            shopChoices.clear();
        }

        private void updateEnemyStatusEffects(double dt) {
            for (Enemy enemy : new ArrayList<>(enemies)) {
                enemy.speedMultiplier = 1.0;
                Iterator<StatusEffect> it = enemy.effects.iterator();
                while (it.hasNext()) {
                    StatusEffect effect = it.next();
                    effect.duration -= dt;
                    effect.tickTimer -= dt;
                    if (effect.type == StatusEffectType.SLOW) {
                        enemy.speedMultiplier = Math.min(enemy.speedMultiplier, 1.0 - effect.modifier);
                    } else if (effect.type == StatusEffectType.BURN && effect.tickTimer <= 0) {
                        damageEnemy(enemy, effect.power);
                        effect.tickTimer = 0.5;
                    }
                    if (effect.duration <= 0) {
                        it.remove();
                    }
                }
            }
        }

        private void updatePlayerStatusEffects(double dt) {
            player.tempDamageBoost = 0.0;
            player.poisonRegenMultiplier = 1.0;
            player.shieldPoints = 0.0;
            Iterator<StatusEffect> it = playerEffects.iterator();
            while (it.hasNext()) {
                StatusEffect effect = it.next();
                effect.duration -= dt;
                if (effect.type == StatusEffectType.POISON) {
                    player.poisonRegenMultiplier = Math.min(player.poisonRegenMultiplier, effect.modifier);
                } else if (effect.type == StatusEffectType.SHIELD) {
                    player.shieldPoints += effect.power;
                } else if (effect.type == StatusEffectType.TEMP_DAMAGE_BOOST) {
                    player.tempDamageBoost += effect.power;
                }
                if (effect.duration <= 0) {
                    it.remove();
                }
            }
        }

        private void addEnemyStatus(Enemy enemy, StatusEffect effect) {
            enemy.effects.add(effect);
        }

        private void addPlayerStatus(StatusEffect effect) {
            playerEffects.add(effect);
        }

        private double absorbShieldDamage(double incoming) {
            if (incoming <= 0 || player.shieldPoints <= 0) return incoming;
            double absorbed = Math.min(player.shieldPoints, incoming);
            player.shieldPoints -= absorbed;
            incoming -= absorbed;
            double toRemove = absorbed;
            Iterator<StatusEffect> it = playerEffects.iterator();
            while (it.hasNext() && toRemove > 0) {
                StatusEffect effect = it.next();
                if (effect.type != StatusEffectType.SHIELD) continue;
                double cut = Math.min(effect.power, toRemove);
                effect.power -= cut;
                toRemove -= cut;
                if (effect.power <= 0.01) it.remove();
            }
            return incoming;
        }

        private void handleMenuConfirm() {
            if (!characterSelectActive) {
                characterSelectActive = true;
                return;
            }
            CharacterDef picked = characters.get(selectedCharacterIdx);
            if (!saveData.unlockedCharacters.contains(picked.name)) {
                if (saveData.totalCoins >= picked.cost) {
                    saveData.totalCoins -= picked.cost;
                    saveData.unlockedCharacters.add(picked.name);
                    saveSystem.save(saveData);
                } else {
                    return;
                }
            }
            boolean load = selectedMenuAction == 1;
            startRunWithCharacter(picked, load);
        }

        private void startRunWithCharacter(CharacterDef picked, boolean load) {
            resetRun();
            unlockedWeapons.clear();
            unlockedWeapons.add(picked.startWeapon);
            player.damageMultiplier *= picked.damageScale;
            player.moveSpeed *= picked.speedScale;
            if (load) {
                score = saveData.savedScore;
                wave = Math.max(1, saveData.savedWave);
                worldTime = Math.max(0, saveData.savedWorldTime);
                runCoins = saveData.savedRunCoins;
            } else {
                runCoins = 0;
                saveData.savedRunCoins = 0;
                saveData.savedScore = 0;
                saveData.savedWave = 1;
                saveData.savedWorldTime = 0;
            }
            saveData.savedCharacter = picked.name;
            characterSelectActive = false;
            gameState = GameState.PLAYING;
        }

        private void resetRun() {
            enemies.clear();
            projectiles.clear();
            xpOrbs.clear();
            damageNumbers.clear();
            upgradeChoices.clear();
            unlockedWeapons.clear();
            unlockedWeapons.add(WeaponType.MAGIC_BOLT);
            chainLightnings.clear();
            sawBlades.clear();
            particles.clear();
            playerEffects.clear();
            shopChoices.clear();
            mapCoins.clear();

            player.reset();
            worldTime = 0;
            spawnTimer = 0.4;
            contactDamageTimer = 0;
            score = 0;
            pendingLevelUps = 0;
            wave = 1;
            waveTimer = 0;
            shotKillStreak = 0;
            noDamageTime = 0;
            coinSpawnTimer = 5.0;
            bossTimer = 180.0;
            extraWeaponCooldowns.clear();
            isDashing = false;
            dashCooldownTimer = 0;
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
            } else if (upgradeState == UpgradeState.PAUSED_FOR_SHOP) {
                drawShopOverlay(g2);
            }
        }

        private void drawBackground(Graphics2D g2) {
            if (bgSprite != null && imagesLoaded) {
                g2.drawImage(bgSprite, 0, 0, WIDTH, HEIGHT, this);
            } else {
                g2.setColor(new Color(15, 15, 22));
                g2.fillRect(0, 0, WIDTH, HEIGHT);
                double offXSlow = (player.x - WIDTH * 0.5) * 0.08;
                double offYSlow = (player.y - HEIGHT * 0.5) * 0.08;
                double offXFast = (player.x - WIDTH * 0.5) * 0.20;
                double offYFast = (player.y - HEIGHT * 0.5) * 0.20;
                for (int i = 0; i < 140; i++) {
                    double x = ((i * 97 + worldTime * 9) % (WIDTH + 200)) - 100 - offXSlow;
                    double y = ((i * 53 + worldTime * 7) % (HEIGHT + 200)) - 100 - offYSlow;
                    g2.setColor(new Color(180, 180, 230, 110));
                    g2.fill(new Ellipse2D.Double(x, y, 2, 2));
                }
                for (int x = -64; x < WIDTH + 64; x += 64) {
                    for (int y = -64; y < HEIGHT + 64; y += 64) {
                        int pulse = (int) ((Math.sin((x + y + worldTime * 45) * 0.03) + 1) * 8);
                        g2.setColor(new Color(30 + pulse, 30 + pulse, 44 + pulse, 120));
                        g2.fillRect((int) (x - offXFast), (int) (y - offYFast), 62, 62);
                    }
                }
            }
        }

        private void drawGame(Graphics2D g2) {
            if (isDashing) {
                g2.setColor(new Color(100, 200, 255, 100));
                g2.fill(new Ellipse2D.Double(player.x - player.radius * 2, player.y - player.radius * 2,
                        player.radius * 4, player.radius * 4));
            }

            // Отрисовка цепной молнии
            for (LightningEffect le : chainLightnings) {
                g2.setColor(new Color(200, 220, 255, 200));
                g2.setStroke(new BasicStroke(4));
                g2.drawLine((int)player.x, (int)player.y, (int)le.target.x, (int)le.target.y);

                if (le.secondTarget != null) {
                    g2.drawLine((int)le.target.x, (int)le.target.y, (int)le.secondTarget.x, (int)le.secondTarget.y);
                }
                if (le.thirdTarget != null) {
                    g2.drawLine((int)le.secondTarget.x, (int)le.secondTarget.y, (int)le.thirdTarget.x, (int)le.thirdTarget.y);
                }
            }

            // Отрисовка пил
            for (SawBladeEffect sb : sawBlades) {
                g2.setColor(new Color(210, 210, 210));
                g2.setStroke(new BasicStroke(2));
                g2.draw(new Ellipse2D.Double(sb.x - sb.radius, sb.y - sb.radius, sb.radius * 2, sb.radius * 2));

                // Линия вращения
                g2.setColor(new Color(210, 210, 210, 100));
                g2.setStroke(new BasicStroke(1));
                double endX = sb.x + Math.cos(sb.angle) * (sb.radius + 5);
                double endY = sb.y + Math.sin(sb.angle) * (sb.radius + 5);
                g2.drawLine((int)sb.x, (int)sb.y, (int)endX, (int)endY);
            }

            for (XpOrb orb : xpOrbs) {
                g2.setColor(new Color(80, 180, 255));
                g2.fill(new Ellipse2D.Double(orb.x - 4, orb.y - 4, 8, 8));
            }
            for (CoinPickup coin : mapCoins) {
                g2.setColor(new Color(250, 210, 70));
                g2.fill(new Ellipse2D.Double(coin.x - 5, coin.y - 5, 10, 10));
                g2.setColor(new Color(255, 240, 140, 180));
                g2.draw(new Ellipse2D.Double(coin.x - 7, coin.y - 7, 14, 14));
            }

            for (Projectile p : projectiles) {
                g2.setColor(new Color(255, 228, 120));
                g2.fill(new Ellipse2D.Double(p.x - p.radius, p.y - p.radius, p.radius * 2, p.radius * 2));
            }
            particleSystem.drawParticles(g2);

            for (Enemy e : enemies) {
                Image spriteToDraw = null;
                boolean isTank = e.radius > 18;

                if (isTank) {
                    spriteToDraw = enemyTankSprite;
                } else {
                    spriteToDraw = enemyNormalSprite;
                }

                double currentRadius = e.getCurrentRadius();

                if (spriteToDraw != null && imagesLoaded) {
                    int w = spriteToDraw.getWidth(this);
                    int h = spriteToDraw.getHeight(this);

                    if (e.hitFlashTimer > 0) {
                        g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.6f));
                        g2.drawImage(spriteToDraw,
                                (int)(e.x - w / 2.0),
                                (int)(e.y - h / 2.0),
                                w, h, this);
                        g2.setComposite(java.awt.AlphaComposite.SrcOver);
                    } else {
                        g2.drawImage(spriteToDraw,
                                (int)(e.x - w / 2.0),
                                (int)(e.y - h / 2.0),
                                w, h, this);
                    }
                } else {
                    if (e.hitFlashTimer > 0) {
                        g2.setColor(new Color(255, 255, 255, 200));
                        g2.fill(new Ellipse2D.Double(
                                e.x - currentRadius,
                                e.y - currentRadius,
                                currentRadius * 2,
                                currentRadius * 2
                        ));
                    } else {
                        g2.setColor(isTank ? new Color(155, 52, 52) : new Color(190, 75, 75));
                        g2.fill(new Ellipse2D.Double(
                                e.x - currentRadius,
                                e.y - currentRadius,
                                currentRadius * 2,
                                currentRadius * 2
                        ));
                    }
                }
                if (e.attackWindup > 0.05) {
                    g2.setColor(new Color(255, 80, 80, (int) (120 * e.attackWindup)));
                    g2.setStroke(new BasicStroke(2));
                    double r = e.radius + 6 + e.attackWindup * 8;
                    g2.draw(new Ellipse2D.Double(e.x - r, e.y - r, r * 2, r * 2));
                }
            }

            if (unlockedWeapons.contains(WeaponType.DAMAGE_AURA)) {
                double aura = player.auraRadius * player.projectileSizeMultiplier;
                g2.setColor(new Color(150, 90, 255, 65));
                g2.fill(new Ellipse2D.Double(player.x - aura, player.y - aura, aura * 2, aura * 2));
            }

            if (playerSprite != null && imagesLoaded) {
                int w = playerSprite.getWidth(this);
                int h = playerSprite.getHeight(this);
                g2.drawImage(playerSprite,
                        (int)(player.x - w / 2.0),
                        (int)(player.y - h / 2.0),
                        w, h, this);
            } else {
                g2.setColor(new Color(80, 230, 120));
                g2.fill(new Ellipse2D.Double(player.x - player.radius, player.y - player.radius,
                        player.radius * 2, player.radius * 2));
            }

            for (DamageNumber dn : damageNumbers) {
                g2.setColor(new Color(255, 255, 255, (int)(dn.alpha * 255)));
                g2.setFont(new Font("Dialog", Font.BOLD, 16));
                g2.drawString(Integer.toString(dn.value), (int)dn.x - 5, (int)dn.y);
            }
        }

        private void drawHud(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(16, 16, 360, 116, 16, 16);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.BOLD, 17));
            g2.drawString("Уровень: " + player.level, 30, 45);
            g2.drawString("Время: " + formatTime(worldTime), 30, 71);
            g2.drawString("Счет: " + score, 30, 97);
            g2.drawString("Волна: " + wave, 250, 45);
            g2.drawString("xKill: " + saveData.bestMultiKill, 250, 71);
            g2.drawString("NoHit: " + formatTime(saveData.bestNoDamageSeconds), 250, 97);
            g2.drawString("Монеты: " + runCoins + " (банк " + saveData.totalCoins + ")", 30, 125);

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

            if (dashCooldownTimer > 0) {
                g2.setColor(new Color(100, 100, 100));
                g2.setFont(new Font("Dialog", Font.PLAIN, 14));
                g2.drawString("Рывок: " + (int)Math.ceil(dashCooldownTimer), 30, 147);
            } else {
                g2.setColor(new Color(100, 255, 100));
                g2.setFont(new Font("Dialog", Font.PLAIN, 14));
                g2.drawString("Рывок: ГОТОВ (ПРОБЕЛ)", 30, 147);
            }
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
                case FROST_NOVA: return new Color(120, 220, 255);
                case TOXIC_DART: return new Color(130, 220, 120);
                case FLAME_ORB: return new Color(255, 150, 90);
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
                case FROST_NOVA: return "ЛВ";
                case TOXIC_DART: return "ЯД";
                case FLAME_ORB: return "ОШ";
                default: return "??";
            }
        }

        private void drawMenu(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, 0, WIDTH, HEIGHT);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.BOLD, 58));
            g2.drawString("JAVA SURVIVORS", 360, 180);

            g2.setFont(new Font("Dialog", Font.PLAIN, 24));
            if (!characterSelectActive) {
                g2.drawString((selectedMenuAction == 0 ? "> " : "  ") + "Новая игра", 500, 290);
                g2.drawString((selectedMenuAction == 1 ? "> " : "  ") + "Загрузить сохранение", 500, 330);
                g2.drawString("↑/↓ выбор, ENTER подтвердить", 430, 400);
                g2.drawString("Банк монет: " + saveData.totalCoins, 500, 440);
            } else {
                g2.drawString("Выбор персонажа (1-0 или ENTER)", 410, 250);
                for (int i = 0; i < characters.size(); i++) {
                    CharacterDef c = characters.get(i);
                    boolean unlocked = saveData.unlockedCharacters.contains(c.name);
                    String line = (i == selectedCharacterIdx ? "> " : "  ") + (i + 1) + ". " + c.name +
                            " [" + c.startWeapon + "] " + (unlocked ? "Открыт" : ("Цена: " + c.cost));
                    g2.drawString(line, 280, 290 + i * 32);
                }
            }
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

        private void drawShopOverlay(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 195));
            g2.fillRect(0, 0, WIDTH, HEIGHT);
            g2.setColor(new Color(255, 230, 170));
            g2.setFont(new Font("Dialog", Font.BOLD, 40));
            g2.drawString("МАГАЗИН МЕЖДУ ВОЛНАМИ", 320, 160);
            g2.setFont(new Font("Dialog", Font.PLAIN, 24));
            g2.setColor(Color.WHITE);
            g2.drawString("Очки: " + score, 540, 205);
            for (int i = 0; i < shopChoices.size(); i++) {
                int y = 280 + i * 95;
                g2.setColor(new Color(80, 62, 40, 220));
                g2.fillRoundRect(300, y - 38, 690, 74, 12, 12);
                g2.setColor(Color.WHITE);
                g2.drawString((i + 1) + ". " + shopChoices.get(i), 330, y + 6);
            }
            g2.setFont(new Font("Dialog", Font.PLAIN, 20));
            g2.drawString("Нажмите 1-3 для покупки, ESC чтобы пропустить", 350, 620);
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
                        handleMenuConfirm();
                    }
                }
                if (gameState == GameState.MENU) {
                    if (!characterSelectActive) {
                        if (code == KeyEvent.VK_UP || code == KeyEvent.VK_W) selectedMenuAction = Math.max(0, selectedMenuAction - 1);
                        if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_S) selectedMenuAction = Math.min(1, selectedMenuAction + 1);
                    } else {
                        if (code == KeyEvent.VK_UP || code == KeyEvent.VK_W) selectedCharacterIdx = Math.max(0, selectedCharacterIdx - 1);
                        if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_S) selectedCharacterIdx = Math.min(characters.size() - 1, selectedCharacterIdx + 1);
                        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) selectedCharacterIdx = Math.min(characters.size() - 1, code - KeyEvent.VK_1);
                        if (code == KeyEvent.VK_0) selectedCharacterIdx = 9;
                    }
                }

                if (code == KeyEvent.VK_SPACE && !isDashing && dashCooldownTimer <= 0) {
                    isDashing = true;
                    dashTimer = DASH_DURATION;
                    dashCooldownTimer = DASH_COOLDOWN;

                    double dx = 0;
                    double dy = 0;
                    if (up) dy -= 1;
                    if (down) dy += 1;
                    if (left) dx -= 1;
                    if (right) dx += 1;

                    if (dx == 0 && dy == 0) {
                        dx = 0;
                        dy = -1;
                    }

                    double len = Math.hypot(dx, dy);
                    if (len > 0) {
                        double dashVx = (dx / len) * player.moveSpeed * DASH_SPEED_MULTIPLIER;
                        double dashVy = (dy / len) * player.moveSpeed * DASH_SPEED_MULTIPLIER;

                        player.x += dashVx * DASH_DURATION;
                        player.y += dashVy * DASH_DURATION;

                        player.x = Math.max(player.radius, Math.min(WIDTH - player.radius, player.x));
                        player.y = Math.max(player.radius, Math.min(HEIGHT - player.radius, player.y));
                    }
                }

                if (upgradeState == UpgradeState.PAUSED_FOR_UPGRADE) {
                    if (code == KeyEvent.VK_1) applyUpgrade(0);
                    if (code == KeyEvent.VK_2) applyUpgrade(1);
                    if (code == KeyEvent.VK_3) applyUpgrade(2);
                }
                if (upgradeState == UpgradeState.PAUSED_FOR_SHOP) {
                    if (code == KeyEvent.VK_1) applyShopChoice(0);
                    if (code == KeyEvent.VK_2) applyShopChoice(1);
                    if (code == KeyEvent.VK_3) applyShopChoice(2);
                    if (code == KeyEvent.VK_ESCAPE) {
                        upgradeState = UpgradeState.NONE;
                        shopChoices.clear();
                    }
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

        private class MouseHandler extends MouseAdapter {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (upgradeState == UpgradeState.PAUSED_FOR_UPGRADE) {
                    int mouseY = e.getY();
                    for (int i = 0; i < upgradeChoices.size(); i++) {
                        int y = 230 + i * 105;
                        if (mouseY >= y - 42 && mouseY <= y + 42) {
                            applyUpgrade(i);
                            break;
                        }
                    }
                }
                if (upgradeState == UpgradeState.PAUSED_FOR_SHOP) {
                    int mouseY = e.getY();
                    for (int i = 0; i < shopChoices.size(); i++) {
                        int y = 280 + i * 95;
                        if (mouseY >= y - 38 && mouseY <= y + 36) {
                            applyShopChoice(i);
                            break;
                        }
                    }
                }
            }
        }
    }

}