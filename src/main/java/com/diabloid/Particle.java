package com.diabloid;

import java.awt.Color;

final class Particle {
    double x, y, vx, vy, size, life, maxLife;
    Color color;
    boolean circle;

    Particle(double x, double y, double vx, double vy, double size, double life, Color color, boolean circle) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.size = size;
        this.life = life;
        this.maxLife = life;
        this.color = color;
        this.circle = circle;
    }
}
