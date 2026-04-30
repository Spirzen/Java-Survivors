package com.diabloid;

final class StatusEffect {
    StatusEffectType type;
    double duration;
    double power;
    double modifier;
    double tickTimer;

    StatusEffect(StatusEffectType type, double duration, double power, double modifier) {
        this.type = type;
        this.duration = duration;
        this.power = power;
        this.modifier = modifier;
        this.tickTimer = 0.5;
    }
}
