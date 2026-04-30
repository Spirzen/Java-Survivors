package com.diabloid;

import java.util.ArrayList;
import java.util.List;

final class Enemy {
    double x;
    double y;
    final double radius;
    double hp;
    final double speed;
    final double contactDamage;
    final int xpValue;
    int score;
    double hitFlashTimer = 0;
    double sizeScale = 1.0;
    double speedMultiplier = 1.0;
    double attackWindup = 0.0;
    final List<StatusEffect> effects = new ArrayList<>();
    EnemyKind kind = EnemyKind.NORMAL;
    double attackCooldown = 0.0;
    int phase = 1;

    Enemy(double x, double y, double radius, double hp, double speed, double contactDamage, int xpValue) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.hp = hp;
        this.speed = speed;
        this.contactDamage = contactDamage;
        this.xpValue = xpValue;
        this.score = xpValue * 3;
    }

    void takeDamage(double damage) {
        this.hp -= damage;
        this.hitFlashTimer = 0.15;
        this.sizeScale = 1.25;
    }

    void updateAnimation(double dt) {
        if (hitFlashTimer > 0) {
            hitFlashTimer -= dt;
            if (sizeScale > 1.0) {
                sizeScale -= dt * 5.0;
                if (sizeScale < 1.0) sizeScale = 1.0;
            }
        } else {
            sizeScale = 1.0;
        }
    }

    double getCurrentRadius() {
        return radius * sizeScale;
    }
}
