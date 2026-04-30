package com.diabloid;

final class Player {
    double x = 640;
    double y = 360;
    double radius = 16;
    double maxHp = 100;
    double hp = 100;
    double regen = 1.0;
    double armorReduction = 0.0;
    double moveSpeed = 240;
    double damageMultiplier = 1.0;
    double attackSpeedMultiplier = 1.0;
    double projectileSpeedMultiplier = 1.0;
    double projectileSizeMultiplier = 1.0;
    double magnetRadius = 105;
    double shotCooldown = 0.2;
    double tripleCooldown = 0.8;
    double pulseCooldown = 1.2;
    double lanceCooldown = 0.6;
    double lightningCooldown = 1.1;
    double sawCooldown = 0.7;
    double auraTickCooldown = 0.2;
    double auraRadius = 82;
    double flatDamageBonus = 0.0;
    double multishotMultiplier = 0.0;
    double frostCooldown = 0.8;
    double toxicCooldown = 0.5;
    double flameCooldown = 0.6;
    double poisonRegenMultiplier = 1.0;
    double shieldPoints = 0.0;
    double tempDamageBoost = 0.0;
    int level = 1;
    int xp = 0;
    int xpToNext = 24;

    void reset() {
        x = 640; y = 360; maxHp = 150; hp = 150; regen = 2.5; armorReduction = 0.05; moveSpeed = 240;
        damageMultiplier = 1.0; attackSpeedMultiplier = 1.0; projectileSpeedMultiplier = 1.0; projectileSizeMultiplier = 1.0;
        magnetRadius = 105; shotCooldown = 0.2; tripleCooldown = 0.8; pulseCooldown = 1.2; lanceCooldown = 0.6; lightningCooldown = 1.1;
        sawCooldown = 0.7; frostCooldown = 0.8; toxicCooldown = 0.5; flameCooldown = 0.6; auraTickCooldown = 0.2; auraRadius = 82;
        flatDamageBonus = 0.0; multishotMultiplier = 0.0; poisonRegenMultiplier = 1.0; shieldPoints = 0.0; tempDamageBoost = 0.0;
        level = 1; xp = 0; xpToNext = 8;
    }

    void heal(double dt) {
        hp = Math.min(maxHp, hp + regen * poisonRegenMultiplier * dt);
    }

    void updateMovement(double dt, boolean up, boolean down, boolean left, boolean right, int maxX, int maxY) {
        double dx = 0, dy = 0;
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
