package com.diabloid;

final class LightningEffect {
    Enemy target;
    Enemy secondTarget;
    Enemy thirdTarget;
    double life = 0.15;

    LightningEffect(Enemy target) {
        this.target = target;
    }

    LightningEffect(Enemy t1, Enemy t2, Enemy t3) {
        this.target = t1;
        this.secondTarget = t2;
        this.thirdTarget = t3;
    }
}
