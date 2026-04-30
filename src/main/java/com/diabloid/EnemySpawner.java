package com.diabloid;

final class EnemySpawner {
    private final DiabloidGame.GamePanel game;

    EnemySpawner(DiabloidGame.GamePanel game) {
        this.game = game;
    }

    void spawnEnemies(double dt) {
        game.spawnEnemies(dt);
    }
}
