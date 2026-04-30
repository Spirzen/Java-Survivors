package com.diabloid;

final class Projectile {
    double x;
    double y;
    final double vx;
    final double vy;
    double damage;
    double radius;
    int pierce;
    double life = 1.8;
    boolean fromEnemy = false;
    boolean appliesSlow = false;
    boolean appliesBurn = false;
    boolean appliesPoison = false;

    Projectile(double x, double y, double vx, double vy, double damage, double radius, int pierce) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.damage = damage;
        this.radius = radius;
        this.pierce = pierce;
    }
}
