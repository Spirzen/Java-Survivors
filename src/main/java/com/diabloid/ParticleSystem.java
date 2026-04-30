package com.diabloid;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.Iterator;

final class ParticleSystem {
    private final DiabloidGame.GamePanel game;
    private double footstepTimer = 0;

    ParticleSystem(DiabloidGame.GamePanel game) {
        this.game = game;
    }

    void spawnKillExplosion(double x, double y) {
        for (int i = 0; i < 18; i++) {
            double angle = game.rng.nextDouble() * Math.PI * 2;
            double speed = 60 + game.rng.nextDouble() * 180;
            Color c = i % 2 == 0 ? new Color(255, 120, 80) : new Color(255, 220, 110);
            game.particles.add(new Particle(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed, 3 + game.rng.nextDouble() * 4, 0.5 + game.rng.nextDouble() * 0.5, c, game.rng.nextBoolean()));
        }
    }

    void spawnProjectileHitSparks(double x, double y) {
        for (int i = 0; i < 8; i++) {
            double angle = game.rng.nextDouble() * Math.PI * 2;
            double speed = 80 + game.rng.nextDouble() * 140;
            game.particles.add(new Particle(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed, 2 + game.rng.nextDouble() * 2, 0.2 + game.rng.nextDouble() * 0.25, new Color(255, 240, 140), true));
        }
    }

    void spawnFootsteps(double dt) {
        footstepTimer -= dt;
        boolean moving = game.up || game.down || game.left || game.right;
        if (!moving || footstepTimer > 0) return;
        footstepTimer = 0.05;
        game.particles.add(new Particle(game.player.x + game.rng.nextDouble() * 8 - 4, game.player.y + game.rng.nextDouble() * 8 - 4, game.rng.nextDouble() * 12 - 6, game.rng.nextDouble() * 12 - 6, 2.5, 0.25, new Color(120, 220, 150, 130), true));
    }

    void updateParticles(double dt) {
        Iterator<Particle> it = game.particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 0.95;
            p.vy *= 0.95;
            if (p.life <= 0) it.remove();
        }
    }

    void drawParticles(Graphics2D g2) {
        for (Particle p : game.particles) {
            float alpha = (float) Math.max(0, p.life / p.maxLife);
            g2.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), (int) (alpha * 255)));
            if (p.circle) {
                g2.fill(new Ellipse2D.Double(p.x - p.size * 0.5, p.y - p.size * 0.5, p.size, p.size));
            } else {
                g2.fillRect((int) (p.x - p.size * 0.5), (int) (p.y - p.size * 0.5), (int) p.size, (int) p.size);
            }
        }
    }
}
