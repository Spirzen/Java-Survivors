package com.diabloid;

final class SawBladeEffect {
    double x, y, angle, speed, radius, damage;
    int pierce;
    double life = 1.8;

    SawBladeEffect(double x, double y, double angle, double speed, double radius, double damage, int pierce) {
        this.x = x;
        this.y = y;
        this.angle = angle;
        this.speed = speed;
        this.radius = radius;
        this.damage = damage;
        this.pierce = pierce;
    }
}
