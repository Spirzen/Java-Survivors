package com.diabloid;

final class WeaponManager {
    private final DiabloidGame.GamePanel game;

    WeaponManager(DiabloidGame.GamePanel game) {
        this.game = game;
    }

    void updateWeapons(double dt) {
        game.updateWeapons(dt);
    }
}
