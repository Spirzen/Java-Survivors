package com.diabloid;

final class CharacterDef {
    String name;
    int cost;
    WeaponType startWeapon;
    double damageScale;
    double speedScale;

    CharacterDef(String name, int cost, WeaponType startWeapon, double damageScale, double speedScale) {
        this.name = name;
        this.cost = cost;
        this.startWeapon = startWeapon;
        this.damageScale = damageScale;
        this.speedScale = speedScale;
    }
}
